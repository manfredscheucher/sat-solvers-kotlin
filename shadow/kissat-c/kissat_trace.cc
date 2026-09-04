/*********************************************************************[kissat_trace.cc]***

  INSTRUMENTED, SELF-CONTAINED reference of KISSAT's CORE CDCL solver.

  Copyright (c) 2019-2024, Armin Biere and the kissat authors (MIT). The algorithm
  here is transcribed from the upstream kissat core search
  (src/{search,decide,propsearch,proplit,analyze,deduce,minimize,learn,backtrack,
  restart,reduce,bump,heap,inlineheap,queue,inlinequeue,assign,inlineassign,averages,
  smooth,reluctant,kimits,tiers,promote,clause,watch}.c/.h), configured for PLAIN
  CDCL with all inprocessing turned OFF, so a deterministic step-by-step trace can
  be produced and shadowed against the matching Kotlin port (kissat/.../Kissat.kt).

  This is the "kissat core" milestone of ksat-ports: NOT the full solver. What is
  transcribed (the CDCL core exactly as kissat implements it, which differs from
  CaDiCaL in several load-bearing ways):
    * UNSIGNED internal literals: lit = 2*idx + sign, NOT(lit) = lit^1, IDX = lit>>1
      (literal.h). values[] is indexed by literal, values[lit] = -values[NOT lit].
    * watched literals with a 2-word large-clause watch encoding (a blocking-literal
      word followed by a clause-reference word) and Ian Gent's saved 'searched'
      position search (proplit.h). Binary clauses are watch-only (no arena clause).
    * conflict analysis (analyze.c): first the conflict-level reuse shortcut
      (one_literal_on_conflict_level) which can reuse the conflict clause as the
      driving clause when exactly one literal sits on the conflict level; otherwise
      the first-UIP deduction over the trail via per-decision-level 'frame.used'
      counters (deduce.c), a level-sorted clause rebuild (sort_deduced_clause), and
      recursive minimization with removable/poisoned flags (minimize.c).
    * kissat's binary max-heap keyed purely by double score with POSITIONAL
      (insertion-order) tie-break -- NOT index tie-break (inlineheap.h bubble_up/
      bubble_down). Used in STABLE mode. In FOCUSED mode a VMTF-style 'stamp' queue
      (queue.c) picks the last-enqueued unassigned variable.
    * EVSIDS-like scores with increment scinc *= 1/(1-decay) and rescale at 1e150
      (bump.c). scinc and every score are `double`.
    * ADAM-style bias-corrected exponential moving averages (smooth.c) of the glue,
      fast (window emafast=33) and slow (window emaslow=1e5), driving Glucose-style
      focused restarts; stable restarts fire on a reluctant (Luby) doubling schedule
      (restart.c, reluctant.c). A stabilizing schedule toggles focused/stable.
    * trail REUSE on restart (restartreusetrail): backtrack only to the level whose
      decision still outranks the next decision variable (restart.c reuse_trail).
    * LBD-tiered reduce keeping tier1 and recently-used tier2 clauses, ranking the
      rest by a packed (~size | ~glue<<32) uint64 key (a total order, radix-sorted),
      and dropping a reducehigh/reducelow-derived fraction (reduce.c). Tier limits
      are recomputed from a glue-usage histogram on a doubling glue interval
      (tiers.c, analyze.c update_tier_limits).
    * phase saving with target/best phases updated on backtrack (backtrack.c).

  DELIBERATELY DISABLED (documented core configuration, so C and Kotlin match; these
  do NOT change the SAT/UNSAT verdict, only the search path, and turning them off
  makes the core reproducible and trace-matchable):
    chronological backtracking (chrono=0 -> kissat_determine_new_level always returns
    the jump level), on-the-fly self-subsumption/strengthening (otfs=0), clause
    shrinking beyond recursive minimize (shrink=0), reason-side bumping (bumpreasons=0),
    eager subsumption of last-learned (eagersubsume=0), random decisions (randec=0),
    jump-reasons, rephasing, reordering, warming, lucky phases, and ALL inprocessing
    (vivify/subsume/eliminate/probe/congruence/sweep/walk/factor + the embedded kitten
    sub-solver).

  The ONLY difference vs a normal build is trace(...) printing one line per
  decision-relevant event, gated by the LSTRACE env var. No algorithm change.

  Shared trace vocabulary (stable; the Kotlin port must match it line-for-line at L1,
  or the decision/event sequence at L2). Literals are emitted as SIGNED DIMACS
  literals (internal unsigned lit -> (idx+1) with sign from lit&1):
    DECIDE <lit>                 a branching decision was assigned (signed DIMACS lit)
    ASSIGN <lit> reason=<D|U|C>  a literal became true. D=decision, U=root unit,
                                 C=implied by a clause (propagation / driving clause).
    CONFLICT                     propagate returned a conflicting clause
    RESTART                      a restart fired
    REDUCE                       reduce ran (useless learned clauses collected)
    RESULT <SAT|UNSAT>           final verdict

*************************************************************************************/

#include <cstdio>
#include <cstdlib>
#include <cmath>
#include <cstdint>
#include <cassert>
#include <vector>
#include <algorithm>

using std::vector;

// --- trace gate ---------------------------------------------------------------
static int TRACE_ON = 0;
static void trace_init () { TRACE_ON = getenv ("LSTRACE") != NULL; }
#define TR(...)                                                                \
  do {                                                                         \
    if (TRACE_ON) {                                                            \
      printf (__VA_ARGS__);                                                    \
    }                                                                          \
  } while (0)

// =============================================================================
// Constants (clause.h / assign.h / literal.h / bump.h / decide.h / statistics.h).
// =============================================================================
static const unsigned INVALID_LIT = 0xffffffffu;
static const unsigned INVALID_IDX = 0xffffffffu;
static const unsigned INVALID_LEVEL = 0xffffffffu;
static const unsigned INVALID_REF = 0xffffffffu;
static const unsigned DECISION_REASON = 0xffffffffu;
static const unsigned UNIT_REASON = 0xfffffffeu;
static const unsigned MAX_GLUE = (1u << 19) - 1;
static const unsigned MAX_USED = (1u << 5) - 1;   // 31
static const int MAX_GLUE_USED = 127;
static const double MAX_SCORE = 1e150;

// literal encoding (literal.h)
static inline unsigned LIT (unsigned idx) { return idx << 1; }
static inline unsigned IDX (unsigned lit) { return lit >> 1; }
static inline unsigned NOT (unsigned lit) { return lit ^ 1u; }
static inline unsigned NEGATED (unsigned lit) { return lit & 1u; }

// =============================================================================
// Options (core configuration; see header comment). Values are upstream defaults.
// =============================================================================
static const int OPT_decay = 50;                // per mille: decay=50 -> 0.050
static const int OPT_stable = 1;                // stable option default (1); ==2 would start stable
static const int OPT_target = 1;                // target phases in stable mode
static const int OPT_phase = 1;                 // initial phase positive
static const int OPT_phasesaving = 1;
static const int OPT_restart = 1;
static const int OPT_restartint = 1;            // focused restart interval base
static const int OPT_restartmargin = 10;        // percent
static const int OPT_reluctant = 1;
static const int64_t OPT_reluctantint = 1024;
static const int64_t OPT_reluctantlim = 1048576;
static const int OPT_reduce = 1;
static const int OPT_reduceint = 300;           // reduce interval base (see NOTE below)
static const int OPT_reducelow = 500;           // per mille -> *0.1 -> 50.0 percent
static const int OPT_reducehigh = 900;          // per mille -> *0.1 -> 90.0 percent
static const int OPT_tier1 = 2;
static const int OPT_tier2 = 6;
static const int OPT_minimize = 1;
static const int OPT_minimizedepth = 1000;
static const int OPT_bump = 1;
static const double OPT_emafast = 33;
static const double OPT_emaslow = 1e5;
// stabilize schedule (mode.c): stableinit conflicts, then doubling deltas.
static const int64_t OPT_stableinit = 1000;

// NOTE on OPT_reduceint: upstream `reduceint` default is 300. The reduce limit
// update is CONFLICTS + reduceint * sqrt(reductions). We expose LSREDUCEINIT to
// lower it for the RESTART/REDUCE trace-coverage test (mirrored by the Kotlin
// reduceIntInit ctor param). Default stays at the upstream 300.
static int64_t g_reduceint = OPT_reduceint;
static int64_t g_stableinit = OPT_stableinit;

static inline int INITIAL_PHASE () { return OPT_phase ? 1 : -1; }

// =============================================================================
// ADAM-style bias-corrected EMA (smooth.c).
// =============================================================================
struct Smooth {
  double value, biased, alpha, beta, exp;
  Smooth () : value (0), biased (0), alpha (0), beta (0), exp (0) {}
  void init (double window) {
    alpha = 1.0 / window;
    value = 0; biased = 0;
    beta = 1.0 - alpha;
    exp = 1.0;
  }
  void update (double y) {
    const double old_biased = biased;
    const double a = alpha;
    const double b = beta;
    const double delta = y - old_biased;
    const double scaled_delta = a * delta;
    const double new_biased = old_biased + scaled_delta;
    biased = new_biased;
    double old_exp = exp;
    double new_value;
    if (old_exp) {
      double new_exp = old_exp * b;
      if (new_exp == old_exp) {
        new_exp = 0;
        new_value = new_biased;
      } else {
        const double div = 1 - new_exp;
        new_value = new_biased / div;
      }
      exp = new_exp;
    } else {
      new_value = new_biased;
    }
    value = new_value;
  }
};

// =============================================================================
// Reluctant (Luby) doubling (reluctant.c).
// =============================================================================
struct Reluctant {
  bool limited, trigger;
  int64_t period, wait, u, v, limit;
  Reluctant () : limited (false), trigger (false),
                 period (0), wait (0), u (0), v (0), limit (0) {}
  void enable (int64_t p, int64_t l) {
    if (l && p > l) p = l;
    limited = (l > 0);
    trigger = false;
    period = p; wait = p;
    u = v = 1;
    limit = l;
  }
  void disable () {
    limited = false; trigger = false;
    period = wait = u = v = limit = 0;
  }
  void tick () {
    if (!period) return;
    if (trigger) return;
    if (--wait) return;
    int64_t uu = u, vv = v;
    if ((uu & -uu) == vv) { uu++; vv = 1; }
    else vv *= 2;
    int64_t w = vv * period;
    if (limited && w > limit) { uu = vv = 1; w = period; }
    trigger = true;
    wait = w;
    u = uu; v = vv;
  }
  bool triggered () {
    if (!trigger) return false;
    trigger = false;
    return true;
  }
};

// =============================================================================
// Clause. Value-faithful (not byte-faithful arena); a "reference" is an index into
// the clause table. Binary clauses are NOT stored here (watch-only), as in kissat.
// =============================================================================
struct Clause {
  unsigned glue;
  bool garbage;
  bool reason;
  bool redundant;
  unsigned used;      // 0..MAX_USED
  unsigned searched;  // Gent saved position (starts at 2)
  unsigned size;
  vector<unsigned> lits;
};

// =============================================================================
// Watch words. A watch list is a flat vector<unsigned> of 32-bit "watch words".
// Binary watch  = 1 word:  (lit<<1)|1        (bit0 = binary flag)
// Large watch   = 2 words:  word0 = (blocking<<1)|0 ; word1 = ref (raw)
//   -> iterate: read w0; binary = w0&1; if !binary read w1.
// (This mirrors kissat's watch union: type.binary is the low bit here for a clean
// self-contained encoding; only the ordering/among-list semantics matter for L1.)
// =============================================================================
static inline unsigned binary_watch_word (unsigned lit) { return (lit << 1) | 1u; }
static inline unsigned blocking_watch_word (unsigned lit) { return (lit << 1); }
static inline bool watch_is_binary (unsigned w) { return (w & 1u) != 0; }
static inline unsigned watch_lit (unsigned w) { return w >> 1; }

// =============================================================================
// The core solver.
// =============================================================================
struct Solver {

  unsigned vars;          // number of variables
  bool inconsistent;
  bool iterating;
  bool stable;
  bool watching;          // always true here (search mode)

  // per-variable / per-literal
  vector<signed char> values;   // indexed by literal (size 2*vars)
  // assigned[idx]
  vector<unsigned> a_level;
  vector<unsigned> a_trail;
  vector<unsigned> a_reason;     // ref, or DECISION_REASON / UNIT_REASON / other-lit (binary)
  vector<char> a_binary;
  vector<char> a_analyzed;
  vector<char> a_poisoned;
  vector<char> a_removable;

  // trail
  vector<unsigned> trail;
  unsigned propagate;      // index into trail
  unsigned level;
  unsigned unassigned;
  unsigned unflushed;

  // frames (decision levels): index by level (0..)
  struct Frame { bool promote; unsigned decision; unsigned trail; unsigned used; };
  vector<Frame> frames;

  // watches: watches[lit] = vector of watch words
  vector<vector<unsigned>> watches;

  // clauses (value-faithful table; index = reference)
  vector<Clause *> clauses;

  // heap (scores), stable mode
  vector<double> score;    // per idx
  double scinc;
  vector<unsigned> heap_stack;
  vector<unsigned> heap_pos;  // idx -> position, DISCONTAIN=UINT_MAX
  bool heap_tainted;

  // queue (VMTF), focused mode
  vector<unsigned> link_prev, link_next, link_stamp;
  unsigned q_first, q_last, q_stamp;
  unsigned q_search_idx, q_search_stamp;

  // phases
  vector<signed char> phase_saved, phase_target, phase_best;
  unsigned best_assigned, target_assigned;

  // analyze scratch
  vector<unsigned> analyzed;
  vector<unsigned> levels;       // decision levels pulled into learned clause
  vector<unsigned> clause;       // learned clause (index 0 = INVALID_LIT placeholder -> not_uip)
  vector<unsigned> shadow;
  vector<unsigned> minimize_stk;
  vector<unsigned> removable_stk;
  vector<unsigned> poisoned_stk;
  vector<unsigned> promote_stk;

  // conflict clause used for binary conflicts (size 2 pseudo-clause)
  Clause bin_conflict;

  // averages (per stable index)
  Smooth avg_fast_glue[2];
  Smooth avg_slow_glue[2];
  bool avg_initialized[2];

  Reluctant reluctant;

  // tiers
  unsigned tier1arr[2], tier2arr[2];
  // glue usage histogram: used_glue[stable][glue]
  vector<uint64_t> used_glue[2];

  // limits
  int64_t conflicts;
  int64_t reductions;
  int64_t restarts;
  int64_t retiered;
  int64_t lim_reduce_conflicts;
  int64_t lim_restart_conflicts;
  int64_t lim_glue_conflicts;
  int64_t lim_glue_interval;
  // stabilize schedule
  int64_t lim_mode_conflicts;
  int64_t mode_count;
  int64_t mode_ticks;

  // model
  vector<signed char> model;

  static constexpr unsigned DISCONTAIN = 0xffffffffu;   // constexpr => inline, no out-of-line def needed
  static constexpr unsigned DISCONNECT = 0xffffffffu;

  Solver () {
    vars = 0;
    inconsistent = false;
    iterating = false;
    stable = false;
    watching = true;
    propagate = 0;
    level = 0;
    unassigned = 0;
    unflushed = 0;
    scinc = 1.0;
    heap_tainted = false;
    q_first = q_last = DISCONNECT;
    q_stamp = 0;
    q_search_idx = DISCONNECT;
    q_search_stamp = 0;
    best_assigned = target_assigned = 0;
    avg_initialized[0] = avg_initialized[1] = false;
    tier1arr[0] = tier1arr[1] = 0;
    tier2arr[0] = tier2arr[1] = 0;
    used_glue[0].assign (MAX_GLUE_USED + 1, 0);
    used_glue[1].assign (MAX_GLUE_USED + 1, 0);
    conflicts = 0;
    reductions = 0;
    restarts = 0;
    retiered = 0;
    lim_reduce_conflicts = 0;
    lim_restart_conflicts = 0;
    lim_glue_conflicts = 0;
    lim_glue_interval = 0;
    lim_mode_conflicts = 0;
    mode_count = 0;
    mode_ticks = 0;
    // root frame
    Frame f; f.promote = false; f.decision = INVALID_LIT; f.trail = 0; f.used = 0;
    frames.push_back (f);
    bin_conflict.glue = 0; bin_conflict.garbage = false; bin_conflict.reason = false;
    bin_conflict.redundant = false; bin_conflict.used = 0; bin_conflict.searched = 2;
    bin_conflict.size = 2; bin_conflict.lits.resize (2);
  }

  // ---- signed DIMACS printing ----
  int dimacs (unsigned lit) const {
    int v = (int) IDX (lit) + 1;
    return NEGATED (lit) ? -v : v;
  }

  // ---- values ----
  signed char value (unsigned lit) const { return values[lit]; }

  // ---- heap (inlineheap.h) : max-heap by double score, positional tie-break ----
  bool heap_contains (unsigned idx) const {
    return idx < heap_pos.size () && heap_pos[idx] != DISCONTAIN;
  }
  double get_heap_score (unsigned idx) const {
    return idx < score.size () ? score[idx] : 0.0;
  }
  void bubble_up (unsigned idx) {
    unsigned idx_pos = heap_pos[idx];
    const double idx_score = score[idx];
    while (idx_pos) {
      const unsigned parent_pos = (idx_pos - 1) / 2;
      const unsigned parent = heap_stack[parent_pos];
      if (score[parent] >= idx_score)
        break;
      heap_stack[idx_pos] = parent;
      heap_pos[parent] = idx_pos;
      idx_pos = parent_pos;
    }
    heap_stack[idx_pos] = idx;
    heap_pos[idx] = idx_pos;
  }
  void bubble_down (unsigned idx) {
    unsigned idx_pos = heap_pos[idx];
    const unsigned end = (unsigned) heap_stack.size ();
    const double idx_score = score[idx];
    for (;;) {
      unsigned child_pos = 2 * idx_pos + 1;
      if (child_pos >= end) break;
      unsigned child = heap_stack[child_pos];
      double child_score = score[child];
      const unsigned sibling_pos = child_pos + 1;
      if (sibling_pos < end) {
        const unsigned sibling = heap_stack[sibling_pos];
        const double sibling_score = score[sibling];
        if (sibling_score > child_score) {
          child = sibling;
          child_pos = sibling_pos;
          child_score = sibling_score;
        }
      }
      if (child_score <= idx_score) break;
      heap_stack[idx_pos] = child;
      heap_pos[child] = idx_pos;
      idx_pos = child_pos;
    }
    heap_stack[idx_pos] = idx;
    heap_pos[idx] = idx_pos;
  }
  void heap_push (unsigned idx) {
    heap_pos[idx] = (unsigned) heap_stack.size ();
    heap_stack.push_back (idx);
    bubble_up (idx);
  }
  unsigned heap_max () const { return heap_stack[0]; }
  unsigned heap_pop_max () {
    const unsigned idx = heap_stack[0];
    const unsigned last = heap_stack.back ();
    heap_stack.pop_back ();
    heap_pos[last] = DISCONTAIN;
    if (last == idx) return idx;
    heap_pos[idx] = DISCONTAIN;
    heap_stack[0] = last;
    heap_pos[last] = 0;
    bubble_down (last);
    return idx;
  }
  void heap_update (unsigned idx, double new_score) {
    const double old_score = get_heap_score (idx);
    if (old_score == new_score) return;
    score[idx] = new_score;
    if (!heap_tainted) heap_tainted = true;
    if (!heap_contains (idx)) return;
    if (new_score > old_score) bubble_up (idx);
    else bubble_down (idx);
  }
  double max_score_on_heap () const {
    if (!heap_tainted) return 0;
    double res = score[0];
    for (unsigned i = 1; i < vars; i++)
      res = std::max (res, score[i]);
    return res;
  }

  // ---- queue (inlinequeue.h) ----
  void update_queue (unsigned idx) {
    q_search_idx = idx;
    q_search_stamp = link_stamp[idx];
  }
  void enqueue_links (unsigned i) {
    const unsigned j = link_prev[i] = q_last;
    q_last = i;
    if (j == DISCONNECT)
      q_first = i;
    else
      link_next[j] = i;
    link_stamp[i] = ++q_stamp;   // (self-contained: no UINT_MAX overflow path needed)
  }
  void dequeue_links (unsigned i) {
    const unsigned j = link_prev[i], k = link_next[i];
    link_prev[i] = link_next[i] = DISCONNECT;
    if (j == DISCONNECT) q_first = k; else link_next[j] = k;
    if (k == DISCONNECT) q_last = j; else link_prev[k] = j;
  }
  void enqueue (unsigned idx) {
    link_prev[idx] = link_next[idx] = DISCONNECT;
    enqueue_links (idx);
    if (!value (LIT (idx)))
      update_queue (idx);
  }
  void move_to_front (unsigned idx) {
    if (idx == q_last) return;
    const signed char tmp = value (LIT (idx));
    if (tmp && q_search_idx == idx) {
      unsigned prev = link_prev[idx];
      if (prev != DISCONNECT) update_queue (prev);
      else { unsigned next = link_next[idx]; update_queue (next); }
    }
    dequeue_links (idx);
    enqueue_links (idx);
    if (!tmp) update_queue (idx);
  }

  // ---- new variable ----
  void ensure_vars (unsigned needed) {
    while (vars < needed) {
      unsigned idx = vars++;
      // grow arrays
      values.resize (2 * vars, 0);
      a_level.resize (vars, 0);
      a_trail.resize (vars, 0);
      a_reason.resize (vars, DECISION_REASON);
      a_binary.resize (vars, 0);
      a_analyzed.resize (vars, 0);
      a_poisoned.resize (vars, 0);
      a_removable.resize (vars, 0);
      watches.resize (2 * vars);
      score.resize (vars, 0.0);
      heap_pos.resize (vars, DISCONTAIN);
      link_prev.resize (vars, DISCONNECT);
      link_next.resize (vars, DISCONNECT);
      link_stamp.resize (vars, 0);
      phase_saved.resize (vars, 0);
      phase_target.resize (vars, 0);
      phase_best.resize (vars, 0);
      unassigned++;
      // register in queue and heap
      enqueue (idx);
      heap_push (idx);
    }
  }

  // ---- watches ----
  void watch_binary (unsigned a, unsigned b) {
    // two binary watches: on NOT(a) watching for a? kissat stores blocking = other.
    // kissat_watch_binary(solver, a, b): pushes binary watch of b onto watches[a]
    // and binary watch of a onto watches[b]. The watched literal list watches[lit]
    // fires when NOT(lit) is propagated.
    watches[a].push_back (binary_watch_word (b));
    watches[b].push_back (binary_watch_word (a));
  }
  void watch_blocking (unsigned lit, unsigned blocking, unsigned ref) {
    watches[lit].push_back (blocking_watch_word (blocking));
    watches[lit].push_back (ref);
  }
  void watch_reference (unsigned a, unsigned b, unsigned ref) {
    watch_blocking (a, b, ref);
    watch_blocking (b, a, ref);
  }
  void unwatch_blocking (unsigned lit, unsigned ref) {
    // remove the (blocking-word, ref-word) pair whose ref == ref from watches[lit]
    vector<unsigned> &ws = watches[lit];
    vector<unsigned> keep;
    keep.reserve (ws.size ());
    size_t i = 0;
    while (i < ws.size ()) {
      unsigned w = ws[i];
      if (watch_is_binary (w)) { keep.push_back (w); i++; continue; }
      unsigned r = ws[i + 1];
      if (r == ref) { i += 2; continue; }
      keep.push_back (w); keep.push_back (r); i += 2;
    }
    ws.swap (keep);
  }

  // ---- clause allocation ----
  unsigned allocate_clause (const vector<unsigned> &lits, bool redundant, unsigned glue) {
    Clause *c = new Clause ();
    c->glue = std::min (MAX_GLUE, glue);
    c->garbage = false;
    c->reason = false;
    c->redundant = redundant;
    c->used = 0;
    c->searched = 2;
    c->size = (unsigned) lits.size ();
    c->lits = lits;
    unsigned ref = (unsigned) clauses.size ();
    clauses.push_back (c);
    return ref;
  }
  Clause *deref (unsigned ref) { return clauses[ref]; }

  // ---- new original clause (size>=3 in arena; size==2 binary watch-only; unit assigns) ----
  // returns true unless UNSAT-at-parse (empty clause)
  bool new_original_clause (const vector<unsigned> &lits) {
    if (lits.empty ()) { inconsistent = true; return false; }
    if (lits.size () == 1) {
      assign_unit (lits[0], UNIT_REASON);
      return true;
    }
    if (lits.size () == 2) {
      watch_binary (lits[0], lits[1]);
      return true;
    }
    unsigned ref = allocate_clause (lits, false, 0);
    Clause *c = deref (ref);
    watch_reference (c->lits[0], c->lits[1], ref);
    return true;
  }

  unsigned new_redundant_clause (unsigned glue) {
    // clause built in solver->clause
    const size_t size = clause.size ();
    if (size == 2) {
      watch_binary (clause[0], clause[1]);
      return INVALID_REF;
    }
    unsigned ref = allocate_clause (clause, true, glue);
    Clause *c = deref (ref);
    watch_reference (c->lits[0], c->lits[1], ref);
    return ref;
  }

  // ---- glue recompute / promote (promote.h / promote.c) ----
  unsigned recompute_glue (Clause *c, unsigned limit) {
    unsigned res = 0;
    promote_stk.clear ();
    for (unsigned lit : c->lits) {
      const unsigned lv = a_level[IDX (lit)];
      Frame &f = frames[lv];
      if (f.promote) continue;
      if (++res == limit) break;
      f.promote = true;
      promote_stk.push_back (lv);
    }
    for (unsigned lv : promote_stk) frames[lv].promote = false;
    promote_stk.clear ();
    return res;
  }
  void promote_clause (Clause *c, unsigned new_glue) {
    c->glue = new_glue;
  }
  void mark_clause_as_used (Clause *c) {
    if (!c->redundant) return;
    c->used = MAX_USED;
    const unsigned old_glue = c->glue;
    const unsigned new_glue = recompute_glue (c, old_glue);
    if (new_glue < old_glue) promote_clause (c, new_glue);
    unsigned glue = std::min (c->glue, (unsigned) MAX_GLUE_USED);
    used_glue[stable ? 1 : 0][glue]++;
  }

  // ---- assign (inlineassign.h) ----
  void do_assign (unsigned level_in, bool binary, unsigned lit, unsigned reason) {
    const unsigned not_lit = NOT (lit);
    values[lit] = 1;
    values[not_lit] = -1;
    unassigned--;
    unsigned lit_level = level_in;
    if (!lit_level) {
      unflushed++;
      if (reason != UNIT_REASON) { reason = UNIT_REASON; binary = false; }
    }
    const unsigned t = (unsigned) trail.size ();
    trail.push_back (lit);
    const unsigned idx = IDX (lit);
    // phase saving
    const signed char new_value = NEGATED (lit) ? (signed char) -1 : (signed char) 1;
    phase_saved[idx] = new_value;
    a_level[idx] = lit_level;
    a_trail[idx] = t;
    a_analyzed[idx] = 0;
    a_binary[idx] = binary ? 1 : 0;
    a_poisoned[idx] = 0;
    a_reason[idx] = reason;
    a_removable[idx] = 0;
  }
  unsigned assignment_level (unsigned lit, Clause *reason) {
    unsigned res = 0;
    for (unsigned other : reason->lits) {
      if (other == lit) continue;
      const unsigned lv = a_level[IDX (other)];
      if (res < lv) res = lv;
    }
    return res;
  }
  void assign_decision (unsigned lit) {
    do_assign (level, false, lit, DECISION_REASON);
    TR ("ASSIGN %d reason=D\n", dimacs (lit));
  }
  void assign_binary (unsigned lit, unsigned other) {
    const unsigned lv = a_level[IDX (other)];
    do_assign (lv, true, lit, other);
    TR ("ASSIGN %d reason=C\n", dimacs (lit));
  }
  void assign_reference (unsigned lit, unsigned ref, Clause *reason) {
    const unsigned lv = assignment_level (lit, reason);
    do_assign (lv, false, lit, ref);
    TR ("ASSIGN %d reason=C\n", dimacs (lit));
  }
  void assign_unit (unsigned lit, unsigned reason) {
    do_assign (0, false, lit, reason);
    TR ("ASSIGN %d reason=U\n", dimacs (lit));
  }
  void learned_unit (unsigned lit) { assign_unit (lit, UNIT_REASON); }

  // ---- frames ----
  void push_frame (unsigned decision) {
    Frame f;
    f.decision = decision;
    f.promote = false;
    f.trail = (unsigned) trail.size ();
    f.used = 0;
    frames.push_back (f);
  }

  // ---- binary conflict pseudo-clause ----
  Clause *binary_conflict (unsigned a, unsigned b) {
    bin_conflict.lits[0] = a;
    bin_conflict.lits[1] = b;
    bin_conflict.size = 2;
    return &bin_conflict;
  }

  // ---- propagate one literal (proplit.h) ----
  Clause *propagate_literal (unsigned lit) {
    const unsigned not_lit = NOT (lit);
    vector<unsigned> &ws = watches[not_lit];
    Clause *res = 0;
    size_t p = 0, q = 0;
    const size_t n = ws.size ();
    while (p != n) {
      const unsigned head = ws[q++] = ws[p++];
      const unsigned blocking = watch_lit (head);
      const signed char blocking_value = values[blocking];
      const bool binary = watch_is_binary (head);
      unsigned tail = 0;
      if (!binary) { tail = ws[q++] = ws[p++]; }
      if (blocking_value > 0) continue;
      if (binary) {
        if (blocking_value < 0) {
          res = binary_conflict (not_lit, blocking);
          break;
        } else {
          assign_binary (blocking, not_lit);
        }
      } else {
        const unsigned ref = tail;
        Clause *c = deref (ref);
        if (c->garbage) { q -= 2; continue; }
        unsigned *lits = c->lits.data ();
        const unsigned other = lits[0] ^ lits[1] ^ not_lit;
        const signed char other_value = values[other];
        if (other_value > 0) {
          // set blocking word to other
          ws[q - 2] = blocking_watch_word (other);
          continue;
        }
        const unsigned size = c->size;
        unsigned r;
        unsigned replacement = INVALID_LIT;
        signed char replacement_value = -1;
        for (r = c->searched; r != size; r++) {
          replacement = lits[r];
          replacement_value = values[replacement];
          if (replacement_value >= 0) break;
        }
        if (replacement_value < 0) {
          for (r = 2; r != c->searched; r++) {
            replacement = lits[r];
            replacement_value = values[replacement];
            if (replacement_value >= 0) break;
          }
        }
        if (replacement_value >= 0) {
          c->searched = r;
          q -= 2;
          lits[0] = other;
          lits[1] = replacement;
          lits[r] = not_lit;
          // delay watching: append (replacement watches this clause, blocking other)
          watch_blocking (replacement, other, ref);
        } else if (other_value) {
          res = c;
          break;
        } else {
          assign_reference (other, ref, c);
        }
      }
    }
    // shift remaining
    while (p != n) ws[q++] = ws[p++];
    ws.resize (q);
    return res;
  }

  // ---- search propagate ----
  Clause *search_propagate () {
    Clause *res = 0;
    while (!res && propagate != trail.size ())
      res = propagate_literal (trail[propagate++]);
    // update conflicts + trail flush
    if (res) {
      conflicts++;
      if (!level) {
        inconsistent = true;
      }
    } else if (!level && unflushed) {
      flush_trail ();
    }
    return res;
  }

  void flush_trail () {
    trail.clear ();
    propagate = 0;
    unflushed = 0;
  }

  // ---- backtrack (backtrack.c) with reuse (target/best updated by caller) ----
  void unassign (unsigned lit) {
    const unsigned not_lit = NOT (lit);
    values[lit] = values[not_lit] = 0;
    unassigned++;
  }
  void backtrack_without_updating_phases (unsigned new_level) {
    if (level == new_level) return;
    const Frame &nf = frames[new_level + 1];
    unsigned new_trail = nf.trail;
    frames.resize (new_level + 1);
    unsigned *tr = trail.data ();
    const unsigned old_end = (unsigned) trail.size ();
    unsigned qidx = new_trail;
    if (stable) {
      for (unsigned pidx = new_trail; pidx != old_end; pidx++) {
        const unsigned lit = tr[pidx];
        const unsigned idx = IDX (lit);
        const unsigned lv = a_level[idx];
        if (lv <= new_level) {
          a_trail[idx] = qidx;
          tr[qidx++] = lit;
        } else {
          unassign (lit);
          if (!heap_contains (idx)) heap_push (idx);
        }
      }
    } else {
      for (unsigned pidx = new_trail; pidx != old_end; pidx++) {
        const unsigned lit = tr[pidx];
        const unsigned idx = IDX (lit);
        const unsigned lv = a_level[idx];
        if (lv <= new_level) {
          a_trail[idx] = qidx;
          tr[qidx++] = lit;
        } else {
          unassign (lit);
          if (link_stamp[idx] > q_search_stamp) update_queue (idx);
        }
      }
    }
    trail.resize (qidx);
    level = new_level;
    propagate = new_trail;
  }
  void update_target_and_best () {
    if (!stable) return;
    const unsigned assigned = vars - unassigned;
    if (target_assigned < assigned) {
      target_assigned = assigned;
      for (unsigned i = 0; i < vars; i++) {
        const signed char tmp = phase_saved[i];
        if (tmp) phase_target[i] = tmp;
      }
    }
    if (best_assigned < assigned) {
      best_assigned = assigned;
      for (unsigned i = 0; i < vars; i++) {
        const signed char tmp = phase_saved[i];
        if (tmp) phase_best[i] = tmp;
      }
    }
  }
  void backtrack_in_consistent_state (unsigned new_level) {
    update_target_and_best ();
    backtrack_without_updating_phases (new_level);
  }
  void backtrack_after_conflict (unsigned new_level) {
    if (level) backtrack_without_updating_phases (level - 1);
    update_target_and_best ();
    backtrack_without_updating_phases (new_level);
  }

  // ---- determine new level (chrono OFF -> always jump) ----
  unsigned determine_new_level (unsigned jump) { return jump; }

  // ---- analyze helpers (deduce.c) ----
  void push_analyzed (unsigned idx) {
    a_analyzed[idx] = 1;
    analyzed.push_back (idx);
  }
  // analyze_literal returns true if this literal is unresolved on the current level.
  bool analyze_literal (unsigned lit) {
    const unsigned idx = IDX (lit);
    const unsigned lv = a_level[idx];
    if (!lv) return false;
    if (a_analyzed[idx]) return false;
    push_analyzed (idx);
    if (lv == level) return true;
    clause.push_back (lit);
    Frame &f = frames[lv];
    if (f.used++) return false;
    levels.push_back (lv);
    return false;
  }

  // deduce first UIP clause. Returns 0 on success (learned clause in solver->clause).
  // (otfs disabled -> never returns a strengthened clause here.)
  void deduce_first_uip_clause (Clause *conflict) {
    if (conflict->size > 2) mark_clause_as_used (conflict);
    clause.clear ();
    clause.push_back (INVALID_LIT);
    unsigned unresolved = 0;
    for (unsigned lit : conflict->lits)
      if (analyze_literal (lit)) unresolved++;
    unsigned tpos = (unsigned) trail.size ();
    unsigned uip = INVALID_LIT;
    for (;;) {
      unsigned idx;
      do {
        uip = trail[--tpos];
        idx = IDX (uip);
      } while (!a_analyzed[idx] || a_level[idx] != level);
      if (unresolved == 1) break;
      idx = IDX (uip);
      if (a_binary[idx]) {
        const unsigned other = a_reason[idx];
        if (analyze_literal (other)) unresolved++;
      } else {
        const unsigned ref = a_reason[idx];
        Clause *reason = deref (ref);
        for (unsigned lit : reason->lits)
          if (lit != uip && analyze_literal (lit)) unresolved++;
        mark_clause_as_used (reason);
      }
      unresolved--;
    }
    clause[0] = NOT (uip);
  }

  // ---- sort_deduced_clause (analyze.c): sort learned clause by DECREASING level ----
  void sort_deduced_clause () {
    // sort solver->levels ascending, then place literals so that per-level blocks are
    // laid out from highest level (front) to lowest, preserving order within a level.
    std::sort (levels.begin (), levels.end ());
    // assign each level a starting position (pos) walking levels from high to low
    unsigned pos = 1;
    for (int i = (int) levels.size () - 1; i >= 0; i--) {
      const unsigned lv = levels[i];
      Frame &f = frames[lv];
      const unsigned used = f.used;
      f.used = pos;    // temporarily repurpose used as running position
      pos += used;
    }
    const size_t size_clause = clause.size ();
    while (shadow.size () < size_clause) shadow.push_back (INVALID_LIT);
    shadow[0] = clause[0];  // not_uip
    for (size_t i = 1; i < clause.size (); i++) {
      const unsigned lit = clause[i];
      const unsigned lv = a_level[IDX (lit)];
      Frame &f = frames[lv];
      const unsigned p = f.used++;
      shadow[p] = lit;
    }
    for (size_t i = 0; i < size_clause; i++) clause[i] = shadow[i];
    // restore f.used to the per-level counts
    pos = 1;
    for (int i = (int) levels.size () - 1; i >= 0; i--) {
      const unsigned lv = levels[i];
      Frame &f = frames[lv];
      const unsigned end = f.used;
      f.used = end - pos;
      pos = end;
    }
    shadow.clear ();
  }

  // ---- minimize (minimize.c) ----
  // minimized_index: >0 keep-as-minimizable(1)/skip, <0 cannot remove, 0 recurse
  int minimized_index (bool minimizing, unsigned idx, unsigned depth) {
    if (!a_level[idx]) return 1;
    if (a_removable[idx] && depth) return 1;
    if (a_reason[idx] == DECISION_REASON) return -1;
    if (a_poisoned[idx]) return -1;
    if (minimizing || !depth) {
      Frame &f = frames[a_level[idx]];
      if (f.used <= 1) return -1;
    }
    return 0;
  }
  bool minimize_literal (bool minimizing, unsigned lit, unsigned depth);
  bool minimize_reference (bool minimizing, unsigned ref, unsigned lit, unsigned depth) {
    const unsigned next_depth = (depth == 0xffffffffu) ? depth : depth + 1;
    const unsigned not_lit = NOT (lit);
    Clause *c = deref (ref);
    for (unsigned other : c->lits)
      if (other != not_lit && !minimize_literal (minimizing, other, next_depth))
        return false;
    return true;
  }
  bool minimize_binary (bool minimizing, unsigned lit, unsigned depth) {
    const size_t saved = minimize_stk.size ();
    bool res;
    unsigned next = lit;
    for (;;) {
      const unsigned next_idx = IDX (next);
      int tmp = minimized_index (minimizing, next_idx, 1);
      if (tmp) { res = (tmp > 0); break; }
      minimize_stk.push_back (next_idx);
      if (!a_binary[next_idx]) {
        const unsigned next_depth = (depth == 0xffffffffu) ? depth : depth + 1;
        res = minimize_reference (minimizing, a_reason[next_idx], next, next_depth);
        break;
      }
      next = a_reason[next_idx];
    }
    for (size_t i = saved; i < minimize_stk.size (); i++) {
      const unsigned idx = minimize_stk[i];
      if (res) { if (!a_removable[idx]) { a_removable[idx] = 1; removable_stk.push_back (idx); } }
      else { if (!a_poisoned[idx]) { a_poisoned[idx] = 1; poisoned_stk.push_back (idx); } }
    }
    minimize_stk.resize (saved);
    return res;
  }

  void minimize_clause () {
    // push all clause literals' idx as removable
    for (unsigned lit : clause) {
      const unsigned idx = IDX (lit);
      if (!a_removable[idx]) { a_removable[idx] = 1; removable_stk.push_back (idx); }
    }
    unsigned n = (unsigned) clause.size ();
    for (unsigned p = n; --p > 0;) {
      const unsigned lit = clause[p];
      if (minimize_literal (true, lit, 0)) clause[p] = INVALID_LIT;
    }
    unsigned q = 0;
    for (unsigned p = 0; p < n; p++) {
      const unsigned lit = clause[p];
      if (lit != INVALID_LIT) clause[q++] = lit;
    }
    clause.resize (q);
    // reset poisoned + removable
    for (unsigned idx : poisoned_stk) a_poisoned[idx] = 0;
    poisoned_stk.clear ();
    for (unsigned idx : removable_stk) a_removable[idx] = 0;
    removable_stk.clear ();
  }

  // ---- bump (bump.c) ----
  void rescale_scores () {
    const double max_score = max_score_on_heap ();
    const double rescale = std::max (max_score, scinc);
    const double factor = 1.0 / rescale;
    for (unsigned i = 0; i < vars; i++) score[i] *= factor;
    scinc *= factor;
  }
  void bump_score_increment () {
    const double old_scinc = scinc;
    const double decay = OPT_decay * 1e-3;
    const double factor = 1.0 / (1.0 - decay);
    const double new_scinc = old_scinc * factor;
    scinc = new_scinc;
    if (new_scinc > MAX_SCORE) rescale_scores ();
  }
  void bump_variable_score (unsigned idx) {
    const double old_score = get_heap_score (idx);
    const double inc = scinc;
    const double new_score = old_score + inc;
    heap_update (idx, new_score);
    if (new_score > MAX_SCORE) rescale_scores ();
  }
  void bump_analyzed () {
    if (!stable) {
      // move analyzed to front of queue, ordered by ascending stamp (stable sort)
      vector<unsigned> order (analyzed.begin (), analyzed.end ());
      std::stable_sort (order.begin (), order.end (),
                        [this] (unsigned a, unsigned b) {
                          return link_stamp[a] < link_stamp[b];
                        });
      for (unsigned idx : order) move_to_front (idx);
    } else {
      for (unsigned idx : analyzed) bump_variable_score (idx);
      bump_score_increment ();
    }
  }

  // ---- learn (learn.c) ----
  void update_learned (unsigned glue, unsigned /*size*/) {
    if (stable) reluctant.tick ();
    avg_fast_glue[stable ? 1 : 0].update ((double) glue);
    avg_slow_glue[stable ? 1 : 0].update ((double) glue);
  }
  void learn_unit (unsigned not_uip) {
    const unsigned new_level = determine_new_level (0);
    backtrack_after_conflict (new_level);
    learned_unit (not_uip);
    iterating = true;
  }
  void learn_binary (unsigned not_uip) {
    const unsigned other = clause[1];
    const unsigned jump_level = a_level[IDX (other)];
    const unsigned new_level = determine_new_level (jump_level);
    backtrack_after_conflict (new_level);
    new_redundant_clause (1);
    assign_binary (not_uip, other);
  }
  void learn_reference (unsigned not_uip, unsigned glue) {
    // find the literal (from index 2..) with the highest level; move it into slot 1
    unsigned *lits = clause.data ();
    unsigned qi = 1;
    unsigned jump_lit = lits[1];
    unsigned jump_level = a_level[IDX (jump_lit)];
    const unsigned end = (unsigned) clause.size ();
    const unsigned backtrack_level = level - 1;
    for (unsigned p = 2; p != end; p++) {
      const unsigned lit = lits[p];
      const unsigned lv = a_level[IDX (lit)];
      if (jump_level >= lv) continue;
      jump_level = lv;
      jump_lit = lit;
      qi = p;
      if (lv == backtrack_level) break;
    }
    lits[qi] = lits[1];
    lits[1] = jump_lit;
    unsigned ref = new_redundant_clause (glue);
    Clause *c = deref (ref);
    c->used = MAX_USED;
    const unsigned new_level = determine_new_level (jump_level);
    backtrack_after_conflict (new_level);
    assign_reference (not_uip, ref, c);
  }
  void learn_clause () {
    const unsigned not_uip = clause[0];
    const unsigned size = (unsigned) clause.size ();
    const unsigned glue = (unsigned) levels.size ();
    update_learned (glue, size);
    if (size == 1) learn_unit (not_uip);
    else if (size == 2) learn_binary (not_uip);
    else learn_reference (not_uip, glue);
  }

  // ---- reset analyzed / analysis ----
  void reset_only_analyzed_literals () {
    for (unsigned idx : analyzed) a_analyzed[idx] = 0;
    analyzed.clear ();
  }
  void reset_levels () {
    for (unsigned lv : levels) frames[lv].used = 0;
    levels.clear ();
  }
  void reset_analysis_but_not_analyzed_literals () {
    reset_levels ();
    clause.clear ();
  }

  // ---- one_literal_on_conflict_level (analyze.c) ----
  // Returns true if the conflict was reused as a driving clause (learning complete).
  // conflict_level_ptr gets the conflict level. May backtrack.
  bool one_literal_on_conflict_level (Clause *conflict, unsigned *conflict_level_ptr) {
    unsigned jump_level = INVALID_LEVEL;
    unsigned conflict_level = INVALID_LEVEL;
    unsigned literals_on_conflict_level = 0;
    unsigned forced_lit = INVALID_LIT;
    unsigned *lits = conflict->lits.data ();
    const unsigned conflict_size = conflict->size;
    for (unsigned i = 0; i < conflict_size; i++) {
      const unsigned lit = lits[i];
      const unsigned lit_level = a_level[IDX (lit)];
      if (conflict_level == INVALID_LEVEL || conflict_level < lit_level) {
        literals_on_conflict_level = 1;
        jump_level = conflict_level;
        conflict_level = lit_level;
        forced_lit = lit;
      } else {
        if (jump_level == INVALID_LEVEL || jump_level < lit_level)
          jump_level = lit_level;
        if (conflict_level == lit_level)
          literals_on_conflict_level++;
      }
      if (literals_on_conflict_level > 1 && conflict_level == level)
        break;
    }
    *conflict_level_ptr = conflict_level;

    if (!conflict_level) { inconsistent = true; return false; }

    if (conflict_level < level)
      backtrack_after_conflict (conflict_level);

    if (conflict_size > 2) {
      for (unsigned i = 0; i < 2; i++) {
        const unsigned lit = lits[i];
        unsigned highest_position = i;
        unsigned highest_literal = lit;
        unsigned highest_level = a_level[IDX (lit)];
        for (unsigned j = i + 1; j < conflict_size; j++) {
          const unsigned other = lits[j];
          const unsigned lv = a_level[IDX (other)];
          if (highest_level >= lv) continue;
          highest_literal = other;
          highest_position = j;
          highest_level = lv;
          if (highest_level == conflict_level) break;
        }
        if (highest_position == i) continue;
        unsigned ref = INVALID_REF;
        if (highest_position > 1) {
          ref = reference_of (conflict);
          unwatch_blocking (lit, ref);
        }
        lits[highest_position] = lit;
        lits[i] = highest_literal;
        if (highest_position > 1)
          watch_blocking (lits[i], lits[!i], ref);
      }
    }

    if (literals_on_conflict_level > 1) return false;

    const unsigned new_level = determine_new_level (jump_level);
    backtrack_after_conflict (new_level);

    if (conflict_size == 2) {
      const unsigned other = lits[0] ^ lits[1] ^ forced_lit;
      assign_binary (forced_lit, other);
    } else {
      const unsigned ref = reference_of (conflict);
      assign_reference (forced_lit, ref, conflict);
    }
    return true;
  }

  // reference of a real (arena) clause pointer
  unsigned reference_of (Clause *c) {
    // linear search is fine for the small shadow instances; c is one of clauses[]
    for (unsigned i = 0; i < clauses.size (); i++)
      if (clauses[i] == c) return i;
    return INVALID_REF;
  }

  // ---- tiers (tiers.c) ----
  void compute_tier_limits (bool st, unsigned *t1p, unsigned *t2p) {
    uint64_t *u = used_glue[st ? 1 : 0].data ();
    uint64_t total = 0;
    for (int g = 0; g <= MAX_GLUE_USED; g++) total += u[g];
    int t1 = -1, t2 = -1;
    if (total) {
      // TIER1RELATIVE = 0.5, TIER2RELATIVE = 0.9 (kissat defaults)
      uint64_t lim1 = (uint64_t) (total * 0.5);
      uint64_t lim2 = (uint64_t) (total * 0.9);
      uint64_t acc = 0;
      int g;
      for (g = 0; g <= MAX_GLUE_USED; g++) {
        acc += u[g];
        if (acc >= lim1) { t1 = g; break; }
      }
      if (acc < lim2) {
        for (g = t1 + 1; g <= MAX_GLUE_USED; g++) {
          acc += u[g];
          if (acc >= lim2) { t2 = g; break; }
        }
      }
    }
    if (t1 < 0) { t1 = OPT_tier1; t2 = std::max (OPT_tier2, OPT_tier1); }
    else if (t2 < 0) t2 = t1;
    *t1p = (unsigned) t1;
    *t2p = (unsigned) t2;
  }
  void compute_and_set_tier_limits () {
    unsigned t1, t2;
    compute_tier_limits (stable, &t1, &t2);
    tier1arr[stable ? 1 : 0] = t1;
    tier2arr[stable ? 1 : 0] = t2;
  }
  unsigned TIER1 () const { return tier1arr[0]; }
  unsigned TIER2 () const { return tier2arr[1]; }

  // ---- reduce (reduce.c) ----
  bool reducing () {
    if (!OPT_reduce) return false;
    // clauses_redundant > 0 ?
    bool any = false;
    for (Clause *c : clauses)
      if (c && c->redundant && !c->garbage) { any = true; break; }
    if (!any) return false;
    return conflicts >= lim_reduce_conflicts;
  }
  void mark_reason_clauses () {
    for (unsigned lit : trail) {
      const unsigned idx = IDX (lit);
      if (a_binary[idx]) continue;
      const unsigned ref = a_reason[idx];
      if (ref == UNIT_REASON || ref == DECISION_REASON) continue;
      deref (ref)->reason = true;
    }
  }
  void unmark_reason_clauses () {
    for (Clause *c : clauses) if (c) c->reason = false;
  }
  int reduce () {
    reductions++;
    compute_and_set_tier_limits ();
    // flush + mark reason clauses (we operate over the whole clause table)
    mark_reason_clauses ();

    const unsigned tier1 = TIER1 ();
    const unsigned tier2 = std::max (tier1, TIER2 ());

    // collect reducibles as (rank, ref)
    vector<std::pair<uint64_t, unsigned>> reds;
    for (unsigned ref = 0; ref < clauses.size (); ref++) {
      Clause *c = clauses[ref];
      if (!c) continue;
      if (!c->redundant) continue;
      if (c->garbage) continue;
      const unsigned used = c->used;
      if (used) c->used = used - 1;
      if (c->reason) continue;
      const unsigned glue = c->glue;
      if (glue <= tier1 && used) continue;
      if (glue <= tier2 && used >= MAX_USED - 1) continue;
      const uint64_t negative_size = ~(uint64_t) c->size & 0xffffffffu;
      const uint64_t negative_glue = ~(uint64_t) c->glue & 0xffffffffu;
      const uint64_t rank = negative_size | (negative_glue << 32);
      reds.push_back (std::make_pair (rank, ref));
    }
    if (!reds.empty ()) {
      // rank ascending. Upstream uses a RADIX sort on the packed uint64 rank, which is
      // a STABLE sort (equal ranks keep insertion/arena order). We mirror that with a
      // stable_sort so equal-rank clauses keep input order identically -- and the Kotlin
      // port uses a stable index sort for the same reason (same lesson as MiniSat/CaDiCaL
      // non-total-order sorts).
      std::stable_sort (reds.begin (), reds.end (),
                        [] (const std::pair<uint64_t, unsigned> &x,
                            const std::pair<uint64_t, unsigned> &y) {
                          return x.first < y.first;
                        });
      // fraction: percent = reducehigh*0.1 - delta/log10(reductions+9), else low
      const double high = OPT_reducehigh * 0.1;
      const double low = OPT_reducelow * 0.1;
      double percent;
      if (low < high) {
        const double delta = high - low;
        percent = high - delta / std::log10 ((double) reductions + 9);
      } else percent = low;
      const double fraction = percent / 100.0;
      size_t size = reds.size ();
      size_t target = (size_t) (size * fraction);
      for (size_t i = 0; i < reds.size () && target; i++, target--) {
        Clause *c = clauses[reds[i].second];
        mark_clause_as_garbage (c);
      }
    }
    sparse_collect ();
    unmark_reason_clauses ();

    // UPDATE_CONFLICT_LIMIT(reduce, reductions, SQRT, false)
    int64_t delta = (int64_t) (g_reduceint * std::sqrt ((double) reductions));
    if (delta < 1) delta = 1;
    lim_reduce_conflicts = conflicts + delta;
    TR ("REDUCE\n");
    return inconsistent ? 20 : 0;
  }
  void mark_clause_as_garbage (Clause *c) {
    if (c->garbage) return;
    c->garbage = true;
    // unwatch (remove the two large watches referencing it)
    unsigned ref = reference_of (c);
    unwatch_blocking (c->lits[0], ref);
    unwatch_blocking (c->lits[1], ref);
  }
  void sparse_collect () {
    // drop garbage clauses; keep the rest (references stay stable: we set entry to 0
    // for garbage so existing refs remain valid, mirroring value-faithfulness).
    for (unsigned ref = 0; ref < clauses.size (); ref++) {
      Clause *c = clauses[ref];
      if (c && c->garbage) { delete c; clauses[ref] = 0; }
    }
  }

  // ---- restart (restart.c) ----
  bool restarting () {
    if (!OPT_restart) return false;
    if (!level) return false;
    if (conflicts < lim_restart_conflicts) return false;
    if (stable) return reluctant.triggered ();
    const double fast = avg_fast_glue[0].value;
    const double slow = avg_slow_glue[0].value;
    const double margin = (100.0 + OPT_restartmargin) / 100.0;
    const double limit = margin * slow;
    return (limit <= fast);
  }
  void update_focused_restart_limit () {
    int64_t delta = OPT_restartint;
    if (restarts) delta += (int64_t) logn ((uint64_t) restarts) - 1;
    lim_restart_conflicts = conflicts + delta;
  }
  unsigned next_decision_variable ();
  unsigned reuse_stable_trail () {
    const unsigned next_idx = next_decision_variable ();
    const double limit = get_heap_score (next_idx);
    unsigned lv = level, res = 0;
    while (res < lv) {
      const Frame &f = frames[res + 1];
      const unsigned idx = IDX (f.decision);
      const double s = get_heap_score (idx);
      if (s <= limit) break;
      res++;
    }
    return res;
  }
  unsigned reuse_focused_trail () {
    const unsigned next_idx = next_decision_variable ();
    const unsigned limit = link_stamp[next_idx];
    unsigned lv = level, res = 0;
    while (res < lv) {
      const Frame &f = frames[res + 1];
      const unsigned idx = IDX (f.decision);
      const unsigned s = link_stamp[idx];
      if (s <= limit) break;
      res++;
    }
    return res;
  }
  unsigned reuse_trail () {
    // restartreusetrail default ON
    return stable ? reuse_stable_trail () : reuse_focused_trail ();
  }
  void restart () {
    restarts++;
    unsigned lv = reuse_trail ();
    backtrack_in_consistent_state (lv);
    if (!stable) update_focused_restart_limit ();
    TR ("RESTART\n");
  }

  // ---- stabilize mode (mode.c switch_search_mode) ----
  static double logn (uint64_t count) { return std::log10 ((double) count + 9); }
  bool switching_search_mode () {
    // simplified stabilize schedule keyed on conflicts, matching mode.c's conflict path.
    return conflicts >= lim_mode_conflicts;
  }
  void init_mode_limit () {
    mode_count++;
    // delta grows quadratically with the phase count (kissat uses ticks; we use the
    // documented conflict schedule: stableinit * count^2)
    int64_t delta = g_stableinit;
    int64_t sp = mode_count;
    delta *= sp * sp;
    lim_mode_conflicts = conflicts + delta;
  }
  void init_averages () {
    if (avg_initialized[stable ? 1 : 0]) return;
    avg_fast_glue[stable ? 1 : 0].init (OPT_emafast);
    avg_slow_glue[stable ? 1 : 0].init (OPT_emaslow);
    avg_initialized[stable ? 1 : 0] = true;
  }
  void update_scores () {
    // push all active unassigned vars onto heap (already pushed at var creation)
    for (unsigned idx = 0; idx < vars; idx++)
      if (!heap_contains (idx)) heap_push (idx);
  }
  void switch_search_mode () {
    stable = !stable;
    init_averages ();
    if (stable) {
      reluctant.enable (OPT_reluctantint, OPT_reluctantlim);
      update_scores ();
    } else {
      reluctant.disable ();
      update_focused_restart_limit ();
    }
    init_mode_limit ();
  }

  // ---- decide (decide.c) ----
  unsigned last_enqueued_unassigned_variable () {
    unsigned res = q_search_idx;
    if (values[LIT (res)]) {
      do { res = link_prev[res]; } while (values[LIT (res)]);
      update_queue (res);
    }
    return res;
  }
  unsigned largest_score_unassigned_variable () {
    unsigned res = heap_max ();
    while (values[LIT (res)]) {
      heap_pop_max ();
      res = heap_max ();
    }
    return res;
  }
  int decide_phase (unsigned idx) {
    signed char res = 0;
    // (focused switched-phase override omitted: switched>>1&7 path -> only fires with
    // stabilize toggles; kept simple & deterministic via saved/target/initial)
    const bool use_target = OPT_target && (stable || OPT_target > 1);
    if (!res && use_target) res = phase_target[idx];
    if (!res && OPT_phasesaving) res = phase_saved[idx];
    if (!res) res = (signed char) INITIAL_PHASE ();
    return res < 0 ? -1 : 1;
  }
  void decide () {
    level++;
    const unsigned idx = next_decision_variable ();
    const int val = decide_phase (idx);
    unsigned lit = LIT (idx);
    if (val < 0) lit = NOT (lit);
    push_frame (lit);
    TR ("DECIDE %d\n", dimacs (lit));
    assign_decision (lit);
  }

  // ---- analyze (analyze.c) ----
  int analyze (Clause *conflict) {
    if (inconsistent) return 20;
    int res;
    do {
      unsigned conflict_level;
      if (one_literal_on_conflict_level (conflict, &conflict_level)) res = 1;
      else if (!conflict_level) res = -1;
      else if (conflict_level == 1) {
        analyze_failed_literal (conflict);
        res = 1;
      } else {
        deduce_first_uip_clause (conflict);
        // otfs disabled -> deduce always "succeeds" (no strengthened clause)
        if (OPT_minimize) {
          sort_deduced_clause ();
          minimize_clause ();
        }
        learn_clause ();
        reset_analysis_but_not_analyzed_literals ();
        res = 1;
      }
      if (!analyzed.empty ()) {
        if (OPT_bump) bump_analyzed ();
        reset_only_analyzed_literals ();
      }
    } while (!res);
    return res > 0 ? 0 : 20;
  }

  // analyze_failed_literal (conflict_level == 1): learn all units on level 1 leading
  // to the negated decision. (transcribed from analyze.c analyze_failed_literal)
  void analyze_failed_literal (Clause *conflict) {
    const unsigned failed = frames[1].decision;
    vector<unsigned> units;
    const unsigned not_failed = NOT (failed);
    unsigned tpos = (unsigned) trail.size ();
    unsigned unresolved = 0;
    unsigned unit = INVALID_LIT;
    bool done = false;
    for (unsigned lit : conflict->lits) {
      if (lit == not_failed) { done = true; break; }
      const unsigned idx = IDX (lit);
      if (!a_level[idx]) continue;
      push_analyzed (idx);
      unresolved++;
    }
    while (!done) {
      unsigned lit;
      unsigned idx;
      do {
        lit = trail[--tpos];
        idx = IDX (lit);
      } while (!a_analyzed[idx]);
      if (unresolved == 1) { unit = NOT (lit); units.push_back (unit); }
      if (a_binary[idx]) {
        const unsigned other = a_reason[idx];
        if (other == not_failed) { done = true; break; }
        const unsigned oidx = IDX (other);
        if (!a_analyzed[oidx]) { push_analyzed (oidx); unresolved++; }
      } else {
        const unsigned ref = a_reason[idx];
        Clause *reason = deref (ref);
        for (unsigned other : reason->lits) {
          if (other == lit) continue;
          if (other == unit) continue;
          if (other == not_failed) { done = true; break; }
          const unsigned oidx = IDX (other);
          if (!a_level[oidx]) continue;
          if (a_analyzed[oidx]) continue;
          push_analyzed (oidx); unresolved++;
        }
        if (done) break;
      }
      unresolved--;
    }
    units.push_back (not_failed);
    backtrack_without_updating_phases (0);
    for (unsigned lit : units) learned_unit (lit);
    iterating = true;
  }

  // ---- update tier limits on glue interval (analyze.c) ----
  void update_tier_limits () {
    retiered++;
    compute_and_set_tier_limits ();
    if (lim_glue_interval < (int64_t) (1u << 16)) lim_glue_interval *= 2;
    lim_glue_conflicts = conflicts + lim_glue_interval;
  }

  // ---- init limits ----
  void init_limits () {
    lim_reduce_conflicts = conflicts + g_reduceint;
    if (!stable) update_focused_restart_limit ();
    init_mode_limit ();
    // tier init
    for (int s = 0; s < 2; s++) {
      if (!tier1arr[s]) {
        tier1arr[s] = OPT_tier1;
        tier2arr[s] = OPT_tier2;
        if (tier2arr[s] <= tier1arr[s]) tier2arr[s] = tier1arr[s];
      }
    }
    if (!lim_glue_interval) lim_glue_interval = 2;
    lim_glue_conflicts = conflicts + lim_glue_interval;
  }

  // ---- top-level search (search.c) ----
  int search () {
    if (inconsistent) { TR ("RESULT UNSAT\n"); return 20; }
    // start_search: kissat sets stable = (GET_OPTION(stable) == 2); the default
    // 'stable' option is 1, so the core starts in FOCUSED mode.
    stable = (OPT_stable == 2);
    init_averages ();
    if (stable) { reluctant.enable (OPT_reluctantint, OPT_reluctantlim); update_scores (); }
    init_limits ();

    int res = 0;
    // initial propagation of any parse-time units already handled during add.
    while (!res) {
      Clause *conflict = search_propagate ();
      if (conflict) {
        res = analyze (conflict);
        // recompute tier limits on the doubling glue interval (analyze.c does this
        // right after a conflict is counted; placed here so C and Kotlin agree).
        if (conflicts > lim_glue_conflicts) update_tier_limits ();
      }
      else if (iterating) { iterating = false; }
      else if (!unassigned) res = 10;
      else if (reducing ()) res = reduce ();
      else if (switching_search_mode ()) switch_search_mode ();
      else if (restarting ()) restart ();
      else decide ();
    }

    if (res == 10) { capture_model (); TR ("RESULT SAT\n"); }
    else { TR ("RESULT UNSAT\n"); }
    return res;
  }

  void capture_model () {
    model.assign (vars, 0);
    for (unsigned i = 0; i < vars; i++)
      model[i] = values[LIT (i)];
  }
};

// -------- out-of-line members that need Solver complete --------
bool Solver::minimize_literal (bool minimizing, unsigned lit, unsigned depth) {
  if (depth >= (unsigned) OPT_minimizedepth) return false;
  const unsigned idx = IDX (lit);
  int tmp = minimized_index (minimizing, idx, depth);
  if (tmp > 0) return true;
  if (tmp < 0) return false;
  bool res;
  if (a_binary[idx]) {
    const unsigned other = a_reason[idx];
    res = minimize_binary (minimizing, other, depth);
  } else {
    const unsigned ref = a_reason[idx];
    res = minimize_reference (minimizing, ref, lit, depth);
  }
  if (!depth) return res;
  if (!res) { if (!a_poisoned[idx]) { a_poisoned[idx] = 1; poisoned_stk.push_back (idx); } }
  else if (!a_removable[idx]) { a_removable[idx] = 1; removable_stk.push_back (idx); }
  return res;
}

unsigned Solver::next_decision_variable () {
  if (stable) return largest_score_unassigned_variable ();
  else return last_enqueued_unassigned_variable ();
}

// =============================================================================
// DIMACS reader.
// =============================================================================
static bool read_dimacs (const char *path, Solver &s) {
  FILE *f = fopen (path, "r");
  if (!f) { fprintf (stderr, "cannot open %s\n", path); return false; }
  int ch;
  int declared_vars = 0, declared_clauses = 0;
  (void) declared_clauses;
  vector<int> clause;
  for (;;) {
    ch = getc (f);
    if (ch == EOF) break;
    if (ch == 'c') { while ((ch = getc (f)) != '\n' && ch != EOF) ; continue; }
    if (ch == 'p') {
      if (fscanf (f, " cnf %d %d", &declared_vars, &declared_clauses) != 2) {
        fprintf (stderr, "bad header\n"); fclose (f); return false;
      }
      s.ensure_vars ((unsigned) declared_vars);
      continue;
    }
    if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') continue;
    ungetc (ch, f);
    int lit;
    if (fscanf (f, "%d", &lit) != 1) break;
    if (lit == 0) {
      // convert to internal literals, drop root-satisfied / root-false, dedup + taut
      vector<unsigned> ps;
      bool taut = false;
      for (int dl : clause) {
        unsigned idx = (unsigned) (dl < 0 ? -dl : dl) - 1;
        s.ensure_vars (idx + 1);
        unsigned ilit = LIT (idx) | (dl < 0 ? 1u : 0u);
        signed char v = s.values[ilit];
        if (v > 0 && s.a_level[idx] == 0) { taut = true; break; }
        if (v < 0 && s.a_level[idx] == 0) continue;
        bool dup = false;
        for (unsigned q : ps) {
          if (q == ilit) { dup = true; break; }
          if (q == NOT (ilit)) { taut = true; break; }
        }
        if (taut) break;
        if (!dup) ps.push_back (ilit);
      }
      clause.clear ();
      if (taut) continue;
      if (!s.new_original_clause (ps)) { /* inconsistent set */ }
      // propagate parse-time units immediately (kissat propagates at root)
      if (!s.inconsistent && s.unflushed) {
        Clause *cf = s.search_propagate ();
        if (cf) { s.inconsistent = true; }
      }
    } else {
      clause.push_back (lit);
    }
  }
  if (!clause.empty ()) {
    vector<unsigned> ps;
    for (int dl : clause) {
      unsigned idx = (unsigned) (dl < 0 ? -dl : dl) - 1;
      s.ensure_vars (idx + 1);
      ps.push_back (LIT (idx) | (dl < 0 ? 1u : 0u));
    }
    s.new_original_clause (ps);
  }
  fclose (f);
  return true;
}

int main (int argc, char **argv) {
  trace_init ();
  if (argc < 2) { fprintf (stderr, "usage: %s <file.cnf>\n", argv[0]); return 1; }
  // env knobs for RESTART/REDUCE trace coverage (mirrored by Kotlin ctor params)
  if (const char *e = getenv ("LSREDUCEINIT")) { long v = atol (e); if (v > 0) g_reduceint = v; }
  if (const char *e = getenv ("LSSTABLEINIT")) { long v = atol (e); if (v > 0) g_stableinit = v; }
  Solver s;
  if (!read_dimacs (argv[1], s)) return 1;
  int res = s.search ();
  if (res == 10) printf ("s SATISFIABLE\n");
  else printf ("s UNSATISFIABLE\n");
  return res == 10 ? 10 : 20;
}
