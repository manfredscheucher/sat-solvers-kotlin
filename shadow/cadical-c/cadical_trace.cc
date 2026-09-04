/*********************************************************************[cadical_trace.cc]***

  INSTRUMENTED, SELF-CONTAINED reference of CaDiCaL's CORE CDCL solver.

  Copyright (c) 2016-2024, Armin Biere and the CaDiCaL authors (MIT). The algorithm
  here is transcribed from the upstream CaDiCaL core search
  (src/{decide,propagate,analyze,minimize,backtrack,restart,reduce,score,queue,
  phases}.cpp, ema.cpp, averages.cpp, heap.hpp, queue.hpp), configured for PLAIN
  CDCL with all inprocessing turned OFF, so a deterministic step-by-step trace can
  be produced and shadowed against the matching Kotlin port (cadical/.../CaDiCaL.kt).

  This is the "CaDiCaL core" milestone of ksat-ports: NOT the full solver. What is
  transcribed (the CDCL core):
    * watched literals with blocking literals and Ian Gent's saved-position search
      (propagate.cpp);
    * conflict analysis via CaDiCaL's 'open'-counter 1st-UIP loop over the trail
      (analyze.cpp) with glue/LBD = number of distinct decision levels - 1;
    * recursive conflict-clause minimization with poison/removable/keep flags
      (minimize.cpp, opts.minimize=1, opts.shrink treated as 0);
    * EVSIDS variable scores in a binary max-heap used in STABLE mode, and the VMTF
      'bumped' decision queue used in FOCUSED mode (score.cpp/queue.hpp,
      use_scores() == opts.score && stable);
    * exponential moving averages (ema.cpp: ADAM-style bias-corrected) of the glue,
      driving Glucose-style restarts; stabilizing phases toggle stable/focused with
      reluctant (Luby) doubling restarts in stable mode (restart.cpp);
    * LBD-tiered reduce keeping tier1 (glue<=2) and recently-used tier2 (glue<=6),
      sorting the rest by (glue,size) and dropping a reducetarget fraction
      (reduce.cpp);
    * phase saving with target/best phases updated on backtrack (backtrack.cpp,
      phases.cpp).

  DELIBERATELY DISABLED (documented core configuration, so C and Kotlin match):
    chronological backtracking (opts.chrono=0), on-the-fly self-subsumption
    (opts.otfs=0), clause shrinking beyond recursive minimize (opts.shrink=0),
    LRAT/DRAT proof, external propagation, reuse-trail, dynamic tier recomputation
    (fixed tier1=2, tier2=6), rephasing, and ALL inprocessing
    (vivify/subsume/elim/probe/congruence/sweep/walk/compact). These do not change
    the SAT/UNSAT verdict; they only change the search path, and turning them off
    makes the core reproducible and trace-matchable.

  The ONLY difference vs a normal build is trace(...) printing one line per
  decision-relevant event, gated by the LSTRACE env var. No algorithm change.

  Shared trace vocabulary (stable; the Kotlin port must match it line-for-line at L1,
  or the decision/event sequence at L2):
    DECIDE <lit>                 a branching decision was assigned (signed DIMACS lit)
    ASSIGN <lit> reason=<D|U|C>  a literal became true. D=decision, U=root unit,
                                 C=implied by a clause (propagation / driving clause).
    CONFLICT                     propagate returned a conflicting clause
    RESTART                      a restart fired (backtrack to level 0)
    REDUCE                       reduce ran (useless learned clauses collected)
    RESULT <SAT|UNSAT>           final verdict
  Literals are the signed DIMACS literal actually made true (internal idx i -> i).

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
// Options (core configuration; see header comment).
// =============================================================================
static const int OPT_score = 1;             // use EVSIDS in stable mode
static const int OPT_stabilize = 1;         // enable stabilizing phases
static const int64_t OPT_stabilizeinit = 1000;
static const int OPT_stabilizefactor = 200; // percent (unused with reluctant path)
static const int OPT_reluctant = 1;
static const int64_t OPT_reluctantint = 1024;
static const int64_t OPT_reluctantmax = 1048576;
static const int OPT_restart = 1;
static const int64_t OPT_restartint = 2;
static const int OPT_restartmarginfocused = 10; // percent
static const int OPT_restartmarginstable = 25;  // percent
static const int OPT_reduce = 1;
static const int64_t OPT_reduceint = 25;
static const int OPT_reducetarget = 75;         // percent
static const int OPT_reducetier1glue = 2;
static const int OPT_reducetier2glue = 6;
static const int OPT_minimize = 1;
static const int OPT_minimizedepth = 1000;
static const int OPT_bump = 1;
static const int OPT_scorefactor = 950;         // per mille
static const int OPT_phase = 1;                 // initial phase positive
static const int OPT_target = 1;                // target phases (stable only)
// EMA windows
static const double OPT_emagluefast = 33;
static const double OPT_emaglueslow = 1e5;

// =============================================================================
// EMA (ema.cpp): ADAM-style bias-corrected exponential moving average.
// =============================================================================
struct EMA {
  double value, biased, alpha, beta, exp;
  EMA () : value (0), biased (0), alpha (0), beta (0), exp (0) {}
  EMA (double a) : value (0), biased (0), alpha (a), beta (1 - a), exp (a != 1 ? 1 : 0) {}
  operator double () const { return value; }
  void update (double y) {
    const double old_biased = biased;
    const double delta = y - old_biased;
    const double scaled_delta = alpha * delta;
    const double new_biased = old_biased + scaled_delta;
    biased = new_biased;
    const double old_exp = exp;
    if (old_exp) {
      const double new_exp = old_exp * beta;
      exp = new_exp;
      const double div = 1 - new_exp;
      value = new_biased / div;
    } else {
      value = new_biased;
    }
  }
};

static inline EMA init_ema (double window) { return EMA (1.0 / window); }

// =============================================================================
// The core solver.
// =============================================================================
struct Clause {
  bool redundant;
  bool garbage;
  bool reason;   // is currently a reason clause (protect from reduce)
  int glue;      // LBD
  int used;      // recently-used counter (tier promotion)
  int pos;       // Gent saved search position
  int size;
  vector<int> lits;
};

struct Watch {
  Clause *clause;
  int blit;   // blocking literal
  bool bin;   // binary clause
};

struct Var {
  int level;
  int trail;
  Clause *reason; // 0 = decision/unit
};

struct Level {
  int decision;
  int trail;
  // 'seen' info for glue/minimize as in CaDiCaL's control frame
  int seen_count;
  int seen_trail;
  Level () : decision (0), trail (0), seen_count (0), seen_trail (0) {}   // for std::vector
  Level (int d, int t) : decision (d), trail (t), seen_count (0), seen_trail (t) {}
};

struct Link {
  int prev, next;
};

// Special sentinel used as reason for a decision (like CaDiCaL's decision_reason).
static Clause decision_reason_clause;
static Clause *const decision_reason = &decision_reason_clause;

struct Solver {

  int max_var;
  // values indexed by literal offset: vals[idx] in {-1,0,+1}
  vector<signed char> vals;   // per variable (index 1..max_var)
  vector<Var> vtab;           // per variable
  // watches: indexed by literal; lit l maps to 2*|l| + (l<0)
  vector<vector<Watch>> wtab;

  vector<int> trail;
  vector<Level> control;
  int level;
  size_t propagated;
  size_t num_assigned;
  Clause *conflict;

  vector<Clause *> clauses;   // all clauses (irredundant + redundant)

  // --- decision heuristics ---
  // EVSIDS scores (stable mode)
  vector<double> stab;        // per variable score
  double score_inc;
  // binary max-heap over variables by score
  vector<unsigned> heap_arr;
  vector<unsigned> heap_pos;  // var -> position, or UINT_MAX
  // VMTF queue (focused mode)
  vector<Link> links;
  int q_first, q_last, q_unassigned;
  int64_t q_bumped;
  vector<int64_t> btab;       // per variable bumped stamp
  int64_t bumped_counter;

  // --- phases ---
  vector<signed char> phase_saved;
  vector<signed char> phase_target;
  vector<signed char> phase_best;
  size_t target_assigned, best_assigned;
  size_t no_conflict_until;

  // --- analyze scratch ---
  vector<char> seen;          // per var
  vector<int> analyzed;       // vars marked seen this analysis
  vector<int> levels_seen;    // levels touched this analysis
  vector<int> clause;         // learned clause literals
  vector<char> removable;     // minimize flags
  vector<char> poison;
  vector<char> keep;
  vector<int> minimized;      // vars touched during minimize

  // --- averages / restarts / reduce ---
  EMA glue_fast, glue_slow;
  bool stable;
  int64_t conflicts;
  int64_t restarts;
  int64_t reductions;
  int64_t stabphases;
  int64_t lim_restart;
  int64_t lim_reduce;
  int64_t lim_stabilize;
  int64_t last_stabilize_conflicts;
  // reluctant doubling (Luby) for stable restarts
  int64_t rel_u, rel_v, rel_period;
  bool rel_trigger;
  bool inc_stabilize_set;
  int64_t inc_stabilize;

  bool unsat;
  bool iterating;

  // model
  vector<signed char> model;

  Solver () {
    max_var = 0;
    level = 0;
    propagated = 0;
    num_assigned = 0;
    conflict = 0;
    score_inc = 1.0;
    q_first = q_last = q_unassigned = 0;
    q_bumped = 0;
    bumped_counter = 0;
    target_assigned = best_assigned = no_conflict_until = 0;
    stable = false;
    conflicts = restarts = reductions = stabphases = 0;
    lim_restart = 0;
    lim_reduce = 0;
    lim_stabilize = 0;
    last_stabilize_conflicts = 0;
    rel_u = rel_v = 1;
    rel_period = 0;
    rel_trigger = false;
    inc_stabilize_set = false;
    inc_stabilize = 0;
    unsat = false;
    iterating = false;
    // variable 0 is unused (1-based like CaDiCaL)
    vals.push_back (0);
    vtab.push_back (Var ());
    stab.push_back (0);
    heap_pos.push_back (0);
    links.push_back (Link ());
    btab.push_back (0);
    phase_saved.push_back (0);
    phase_target.push_back (0);
    phase_best.push_back (0);
    seen.push_back (0);
    removable.push_back (0);
    poison.push_back (0);
    keep.push_back (0);
    control.push_back (Level (0, 0)); // root frame
  }

  // ---- literal / watch helpers ----
  static int vidx (int lit) { return lit < 0 ? -lit : lit; }
  int litoff (int lit) const { return 2 * vidx (lit) + (lit < 0 ? 1 : 0); }
  vector<Watch> &watches (int lit) { return wtab[litoff (lit)]; }
  signed char val (int lit) const {
    const signed char v = vals[vidx (lit)];
    return lit < 0 ? (signed char) -v : v;
  }
  Var &var (int lit) { return vtab[vidx (lit)]; }

  // ---- heap (max-heap by score, heap.hpp) ----
  static constexpr unsigned INVALID = 0xffffffffu;   // constexpr => inline, no out-of-line def needed
  bool heap_contains (unsigned e) const {
    if ((size_t) e >= heap_pos.size ())
      return false;
    return heap_pos[e] != INVALID;
  }
  bool heap_less (unsigned a, unsigned b) const {
    // 'less' means a is smaller (worse). Max-heap keeps larger score on top.
    // CaDiCaL's score_smaller: larger score, tie-break by larger index.
    if (stab[a] < stab[b])
      return true;
    if (stab[a] > stab[b])
      return false;
    return a < b;
  }
  void heap_exchange (unsigned a, unsigned b) {
    unsigned &i = heap_pos[a];
    unsigned &j = heap_pos[b];
    std::swap (heap_arr[i], heap_arr[j]);
    std::swap (i, j);
  }
  void heap_up (unsigned e) {
    unsigned p;
    while (heap_pos[e] > 0 &&
           heap_less ((p = heap_arr[(heap_pos[e] - 1) / 2]), e))
      heap_exchange (p, e);
  }
  void heap_down (unsigned e) {
    while ((size_t) 2 * heap_pos[e] + 1 < heap_arr.size ()) {
      unsigned c = heap_arr[2 * heap_pos[e] + 1];
      if ((size_t) 2 * heap_pos[e] + 2 < heap_arr.size ()) {
        unsigned r = heap_arr[2 * heap_pos[e] + 2];
        if (heap_less (c, r))
          c = r;
      }
      if (!heap_less (e, c))
        break;
      heap_exchange (e, c);
    }
  }
  void heap_push (unsigned e) {
    size_t i = heap_arr.size ();
    heap_arr.push_back (e);
    heap_pos[e] = (unsigned) i;
    heap_up (e);
    heap_down (e);
  }
  unsigned heap_front () const { return heap_arr[0]; }
  unsigned heap_pop () {
    unsigned res = heap_arr[0], last = heap_arr.back ();
    if (heap_arr.size () > 1)
      heap_exchange (res, last);
    heap_pos[res] = INVALID;
    heap_arr.pop_back ();
    if (heap_arr.size () > 1)
      heap_down (last);
    return res;
  }
  void heap_update (unsigned e) {
    heap_up (e);
    heap_down (e);
  }

  // ---- VMTF queue ----
  void update_queue_unassigned (int idx) {
    q_unassigned = idx;
    q_bumped = btab[idx];
  }
  void q_dequeue (int idx) {
    Link &l = links[idx];
    if (l.prev)
      links[l.prev].next = l.next;
    else
      q_first = l.next;
    if (l.next)
      links[l.next].prev = l.prev;
    else
      q_last = l.prev;
  }
  void q_enqueue (int idx) {
    Link &l = links[idx];
    if ((l.prev = q_last))
      links[q_last].next = idx;
    else
      q_first = idx;
    q_last = idx;
    l.next = 0;
  }
  void init_enqueue (int idx) {
    Link &l = links[idx];
    l.next = 0;
    if (q_last) {
      links[q_last].next = idx;
    } else {
      q_first = idx;
    }
    btab[idx] = ++bumped_counter;
    l.prev = q_last;
    q_last = idx;
    update_queue_unassigned (q_last);
  }

  bool use_scores () const { return OPT_score && stable; }

  // ---- new variable ----
  void new_var () {
    int idx = ++max_var;
    vals.push_back (0);
    Var v;
    v.level = 0;
    v.trail = 0;
    v.reason = 0;
    vtab.push_back (v);
    wtab.resize (2 * (max_var + 1));
    stab.push_back (0);
    if ((size_t) idx >= heap_pos.size ())
      heap_pos.resize (idx + 1, INVALID);
    heap_pos[idx] = INVALID;
    links.push_back (Link ());
    btab.push_back (0);
    phase_saved.push_back (0);
    phase_target.push_back (0);
    phase_best.push_back (0);
    seen.push_back (0);
    removable.push_back (0);
    poison.push_back (0);
    keep.push_back (0);
    // register in both structures (queue always, heap always so both work)
    init_enqueue (idx);
    heap_push ((unsigned) idx);
  }
  void ensure_var (int idx) {
    while (max_var < idx)
      new_var ();
  }

  // ---- scores (EVSIDS) ----
  bool evsids_limit_hit (double s) const { return s > 1e150; }
  void rescale_variable_scores () {
    double divider = score_inc;
    for (int idx = 1; idx <= max_var; idx++) {
      const double tmp = stab[idx];
      if (tmp > divider)
        divider = tmp;
    }
    double factor = 1.0 / divider;
    for (int idx = 1; idx <= max_var; idx++)
      stab[idx] *= factor;
    score_inc *= factor;
  }
  void bump_variable_score (int lit) {
    int idx = vidx (lit);
    double old_score = stab[idx];
    double new_score = old_score + score_inc;
    if (evsids_limit_hit (new_score)) {
      rescale_variable_scores ();
      old_score = stab[idx];
      new_score = old_score + score_inc;
    }
    stab[idx] = new_score;
    if (heap_contains ((unsigned) idx))
      heap_update ((unsigned) idx);
  }
  void bump_variable_score_inc () {
    double f = 1e3 / (double) OPT_scorefactor;
    double new_score_inc = score_inc * f;
    if (evsids_limit_hit (new_score_inc)) {
      rescale_variable_scores ();
      new_score_inc = score_inc * f;
    }
    score_inc = new_score_inc;
  }
  // ---- VMTF bump ----
  void bump_queue (int lit) {
    const int idx = vidx (lit);
    if (!links[idx].next)
      return;
    q_dequeue (idx);
    q_enqueue (idx);
    btab[idx] = ++bumped_counter;
    if (!vals[idx])
      update_queue_unassigned (idx);
  }
  void bump_variable (int lit) {
    if (use_scores ())
      bump_variable_score (lit);
    else
      bump_queue (lit);
  }
  // bumped rank for sorting analyzed in queue mode
  int64_t bumped (int lit) const { return btab[vidx (lit)]; }

  void bump_variables () {
    if (!use_scores ()) {
      // sort analyzed by bumped ascending (stable) -- CaDiCaL bumps in queue order
      std::stable_sort (analyzed.begin (), analyzed.end (),
                        [this] (int a, int b) { return bumped (a) < bumped (b); });
    }
    for (int lit : analyzed)
      bump_variable (lit);
    if (use_scores ())
      bump_variable_score_inc ();
  }

  // ---- assign ----
  void assign (int lit, Clause *reason, char rc) {
    const int idx = vidx (lit);
    Var &v = var (lit);
    int lit_level;
    if (!reason)
      lit_level = 0; // unit
    else if (reason == decision_reason) {
      lit_level = level;
      reason = 0;
    } else {
      lit_level = level; // no chrono: assignment level == current level
    }
    if (!lit_level)
      reason = 0;
    v.level = lit_level;
    v.trail = (int) trail.size ();
    v.reason = reason;
    num_assigned++;
    const signed char tmp = (lit < 0) ? -1 : 1;
    vals[idx] = tmp;
    phase_saved[idx] = tmp;
    trail.push_back (lit);
    TR ("ASSIGN %d reason=%c\n", lit, rc);
  }

  void new_trail_level (int lit) {
    level++;
    control.push_back (Level (lit, (int) trail.size ()));
  }

  // ---- watch ----
  void watch_literal (int lit, int blit, Clause *c) {
    Watch w;
    w.clause = c;
    w.blit = blit;
    w.bin = (c->size == 2);
    watches (lit).push_back (w);
  }
  void watch_clause (Clause *c) {
    const int l0 = c->lits[0];
    const int l1 = c->lits[1];
    watch_literal (l0, l1, c);
    watch_literal (l1, l0, c);
  }
  void remove_watch (vector<Watch> &ws, Clause *c) {
    for (size_t i = 0; i < ws.size (); i++)
      if (ws[i].clause == c) {
        ws.erase (ws.begin () + i);
        return;
      }
  }
  void unwatch_clause (Clause *c) {
    remove_watch (watches (c->lits[0]), c);
    remove_watch (watches (c->lits[1]), c);
  }

  // ---- new clause ----
  Clause *new_clause (const vector<int> &lits, bool redundant, int glue) {
    Clause *c = new Clause ();
    c->redundant = redundant;
    c->garbage = false;
    c->reason = false;
    c->glue = glue;
    c->used = redundant ? 1 : 0;
    c->pos = 2;
    c->size = (int) lits.size ();
    c->lits = lits;
    clauses.push_back (c);
    if (c->size >= 2)
      watch_clause (c);
    return c;
  }

  // ---- propagate (propagate.cpp) ----
  bool propagate () {
    int64_t before = (int64_t) propagated;
    while (!conflict && propagated != trail.size ()) {
      const int lit = -trail[propagated++];
      vector<Watch> &ws = watches (lit);
      size_t i = 0, j = 0;
      while (i != ws.size ()) {
        Watch w = ws[j++] = ws[i++];
        const signed char b = val (w.blit);
        if (b > 0)
          continue; // blocking literal satisfied
        if (w.bin) {
          if (b < 0)
            conflict = w.clause;
          else
            assign (w.blit, w.clause, 'C');
        } else {
          if (conflict)
            break;
          if (w.clause->garbage) {
            j--;
            continue;
          }
          vector<int> &lits = w.clause->lits;
          const int other = lits[0] ^ lits[1] ^ lit;
          const signed char u = val (other);
          if (u > 0) {
            ws[j - 1].blit = other;
          } else {
            const int size = w.clause->size;
            int mid = w.clause->pos;
            int k = mid;
            int r = 0;
            signed char v = -1;
            while (k != size && (v = val (r = lits[k])) < 0)
              k++;
            if (v < 0) {
              k = 2;
              while (k != mid && (v = val (r = lits[k])) < 0)
                k++;
            }
            w.clause->pos = k;
            if (v > 0) {
              ws[j - 1].blit = r;
            } else if (!v) {
              // found new unassigned replacement literal to watch
              lits[0] = other;
              lits[1] = r;
              lits[k] = lit;
              watch_literal (r, lit, w.clause);
              j--;
            } else if (!u) {
              // other watch unassigned, rest false -> unit
              assign (other, w.clause, 'C');
            } else {
              // conflict
              conflict = w.clause;
              break;
            }
          }
        }
      }
      if (j != i) {
        while (i != ws.size ())
          ws[j++] = ws[i++];
        ws.resize (j);
      }
    }
    if (!conflict) {
      no_conflict_until = propagated;
    } else {
      conflicts++;
      no_conflict_until = control[level].trail;
      TR ("CONFLICT\n");
    }
    (void) before;
    return !conflict;
  }

  // ---- analyze support ----
  int recompute_glue (Clause *c) {
    // count distinct decision levels among literals of c
    static int64_t stamp_counter = 0;
    static vector<int64_t> gtab;
    int64_t stamp = ++stamp_counter;
    if ((int) gtab.size () < level + 1)
      gtab.resize (level + 1, 0);
    int res = 0;
    for (int lit : c->lits) {
      int lv = var (lit).level;
      if (lv >= (int) gtab.size ())
        gtab.resize (lv + 1, 0);
      if (gtab[lv] == stamp)
        continue;
      gtab[lv] = stamp;
      res++;
    }
    return res;
  }
  void promote_clause (Clause *c, int new_glue) {
    if (new_glue < c->glue)
      c->glue = new_glue;
  }
  int max_used_val () const { return 2; } // tier2 -> used 2 (simplified)
  void bump_clause (Clause *c) {
    c->used = max_used_val ();
    if (!c->redundant)
      return;
    int new_glue = recompute_glue (c);
    if (new_glue < c->glue)
      promote_clause (c, new_glue);
  }

  // analyze_literal: process 'lit' (assigned false) appearing in a reason.
  void analyze_literal (int lit, int &open) {
    Var &v = var (lit);
    if (!v.level)
      return; // root-level fixed literal dropped
    if (seen[vidx (lit)])
      return;
    seen[vidx (lit)] = 1;
    analyzed.push_back (lit);
    if (v.level < level)
      clause.push_back (lit);
    Level &l = control[v.level];
    if (!l.seen_count++) {
      levels_seen.push_back (v.level);
      l.seen_trail = v.trail;
    }
    if (v.trail < l.seen_trail)
      l.seen_trail = v.trail;
    if (v.level == level)
      open++;
  }
  void analyze_reason (int lit, Clause *reason, int &open) {
    bump_clause (reason);
    for (int other : reason->lits)
      if (other != lit)
        analyze_literal (other, open);
  }

  void clear_analyzed_literals () {
    for (int lit : analyzed)
      seen[vidx (lit)] = 0;
    analyzed.clear ();
  }
  void clear_analyzed_levels () {
    for (int l : levels_seen)
      if (l < (int) control.size ()) {
        control[l].seen_count = 0;
        control[l].seen_trail = control[l].trail;
      }
    levels_seen.clear ();
  }

  // ---- recursive minimization (minimize.cpp) ----
  bool minimize_literal (int lit, int depth) {
    Var &v = var (lit);
    int idx = vidx (lit);
    if (!v.level || removable[idx] || keep[idx])
      return true;
    if (!v.reason || poison[idx] || v.level == level)
      return false;
    const Level &l = control[v.level];
    if (!depth && l.seen_count < 2)
      return false;
    if (v.trail <= l.seen_trail)
      return false;
    if (depth > OPT_minimizedepth)
      return false;
    bool res = true;
    for (int other : v.reason->lits) {
      if (other == lit)
        continue;
      if (!(res = minimize_literal (-other, depth + 1)))
        break;
    }
    if (res)
      removable[idx] = 1;
    else
      poison[idx] = 1;
    minimized.push_back (idx);
    return res;
  }

  void minimize_clause () {
    // sort clause by trail order (ascending trail)
    std::sort (clause.begin (), clause.end (),
               [this] (int a, int b) { return var (a).trail < var (b).trail; });
    size_t j = 0;
    for (size_t i = 0; i < clause.size (); i++) {
      int lit = clause[i];
      if (minimize_literal (-lit, 0)) {
        // removed
      } else {
        keep[vidx (lit)] = 1;
        clause[j++] = lit;
      }
    }
    clause.resize (j);
    // clear minimize flags
    for (int idx : minimized) {
      removable[idx] = 0;
      poison[idx] = 0;
    }
    for (int lit : clause)
      keep[vidx (lit)] = 0;
    minimized.clear ();
  }

  // ---- new driving clause ----
  Clause *new_driving_clause (int glue, int &jump) {
    const size_t size = clause.size ();
    Clause *res;
    if (size == 0) {
      jump = 0;
      res = 0;
    } else if (size == 1) {
      jump = 0;
      res = 0;
    } else {
      // sort clause by decreasing (level,trail) so highest-level lits go first
      std::sort (clause.begin (), clause.end (), [this] (int a, int b) {
        Var &va = var (a);
        Var &vb = var (b);
        if (va.level != vb.level)
          return va.level > vb.level;
        return va.trail > vb.trail;
      });
      jump = var (clause[1]).level;
      res = new_clause (clause, true, glue);
    }
    return res;
  }

  // ---- backtrack ----
  void copy_phases (vector<signed char> &dst) {
    for (int i = 1; i <= max_var; i++) {
      const signed char tmp = phase_saved[i];
      if (tmp)
        dst[i] = tmp;
    }
  }
  void update_target_and_best () {
    if (!stable)
      return; // opts.rephase==2 default only updates in stable
    if (no_conflict_until > target_assigned) {
      copy_phases (phase_target);
      target_assigned = no_conflict_until;
    }
    if (no_conflict_until > best_assigned) {
      copy_phases (phase_best);
      best_assigned = no_conflict_until;
    }
  }
  void unassign (int lit) {
    const int idx = vidx (lit);
    vals[idx] = 0;
    num_assigned--;
    if (!heap_contains ((unsigned) idx))
      heap_push ((unsigned) idx);
    if (q_bumped < btab[idx])
      update_queue_unassigned (idx);
  }
  void backtrack (int new_level) {
    if (new_level == level)
      return;
    update_target_and_best ();
    const size_t assigned = control[new_level + 1].trail;
    const size_t end = trail.size ();
    for (size_t i = assigned; i < end; i++)
      unassign (trail[i]);
    trail.resize (assigned);
    if (propagated > assigned)
      propagated = assigned;
    if (no_conflict_until > assigned)
      no_conflict_until = assigned;
    control.resize (new_level + 1);
    level = new_level;
  }

  // ---- analyze (analyze.cpp, no-chrono/no-otfs path) ----
  void analyze () {
    // conflict level == current level (no chrono)
    // find highest-level literal (for driving); ensure two highest in front not
    // needed here without chrono. We use standard 1-UIP over the trail.

    // Actual root-level conflict -> UNSAT.
    if (!level) {
      unsat = true;
      conflict = 0;
      return;
    }

    Clause *reason = conflict;
    int i = (int) trail.size ();
    int open = 0;
    int uip = 0;

    for (;;) {
      analyze_reason (uip, reason, open);
      uip = 0;
      while (!uip) {
        const int lit = trail[--i];
        if (!seen[vidx (lit)])
          continue;
        if (var (lit).level == level)
          uip = lit;
      }
      if (!--open)
        break;
      reason = var (uip).reason;
    }
    clause.push_back (-uip);

    int size = (int) clause.size ();
    const int glue = (int) levels_seen.size () - 1;
    glue_fast.update ((double) glue);
    glue_slow.update ((double) glue);

    if (size > 1) {
      if (OPT_minimize)
        minimize_clause ();
      size = (int) clause.size ();
      if (OPT_bump)
        bump_variables ();
    }

    int jump;
    Clause *driving = new_driving_clause (glue, jump);
    backtrack (jump);

    if (uip) {
      // assign flipped 1st UIP with driving clause (or as unit if size 1)
      assign (-uip, driving, 'C');
    } else {
      unsat = true;
    }

    if (stable)
      reluctant_tick ();

    clear_analyzed_literals ();
    clear_analyzed_levels ();
    clause.clear ();
    conflict = 0;

    if (size == 1)
      iterating = true;
  }

  // ---- reluctant (Luby) doubling ----
  void reluctant_enable () {
    rel_u = rel_v = 1;
    rel_period = OPT_reluctantint;
    rel_trigger = false;
  }
  void reluctant_disable () {
    rel_u = rel_v = rel_period = 0;
    rel_trigger = false;
  }
  void reluctant_tick () {
    if (!rel_period)
      return;
    if (--rel_period)
      return;
    if ((rel_u & -rel_u) == rel_v) {
      rel_u++;
      rel_v = 1;
    } else {
      rel_v *= 2;
    }
    int64_t p = rel_v;
    if (OPT_reluctantmax && p > OPT_reluctantmax / OPT_reluctantint) {
      rel_u = rel_v = 1;
      p = 1;
    }
    rel_period = p * OPT_reluctantint;
    rel_trigger = true;
  }

  // ---- stabilizing (restart.cpp) ----
  bool stabilizing () {
    if (!OPT_stabilize)
      return false;
    if (conflicts <= lim_stabilize)
      return stable;
    // reached limit -> toggle
    int64_t delta_conflicts = conflicts - last_stabilize_conflicts;
    if (!inc_stabilize_set) {
      inc_stabilize = delta_conflicts;
      if (inc_stabilize < 1)
        inc_stabilize = 1;
      inc_stabilize_set = true;
    }
    int64_t next_delta = inc_stabilize;
    int64_t sp = stabphases + 1;
    next_delta *= sp * sp;
    lim_stabilize = conflicts + next_delta;
    if (lim_stabilize <= conflicts)
      lim_stabilize = conflicts + 1;
    last_stabilize_conflicts = conflicts;
    stable = !stable;
    if (stable)
      stabphases++;
    swap_averages ();
    if (stable)
      reluctant_enable ();
    else
      reluctant_disable ();
    return stable;
  }

  // ---- restart (restart.cpp) ----
  bool restarting () {
    if (!OPT_restart)
      return false;
    if (level < 2)
      return false;
    if (stabilizing () && OPT_reluctant)
      return rel_trigger;
    if (conflicts <= lim_restart)
      return false;
    double f = glue_fast.value;
    int p = stable ? OPT_restartmarginstable : OPT_restartmarginfocused;
    double m = (100.0 + p) / 100.0;
    double s = glue_slow.value;
    double l = m * s;
    return l <= f;
  }
  void restart () {
    restarts++;
    rel_trigger = false;
    backtrack (0);
    lim_restart = conflicts + OPT_restartint;
    TR ("RESTART\n");
  }

  // ---- averages swap on mode switch ----
  bool averages_swapped;
  EMA saved_glue_fast, saved_glue_slow;
  void init_averages () {
    glue_fast = init_ema (OPT_emagluefast);
    glue_slow = init_ema (OPT_emaglueslow);
  }
  void swap_averages () {
    std::swap (glue_fast, saved_glue_fast);
    std::swap (glue_slow, saved_glue_slow);
    if (!averages_swapped)
      init_averages ();
    averages_swapped = true;
  }

  // ---- reduce (reduce.cpp) ----
  bool reducing () {
    if (!OPT_reduce)
      return false;
    // need at least one redundant clause
    bool any = false;
    for (Clause *c : clauses)
      if (c->redundant && !c->garbage) {
        any = true;
        break;
      }
    if (!any)
      return false;
    return conflicts >= lim_reduce;
  }
  void protect_reasons () {
    for (int lit : trail) {
      Clause *r = var (lit).reason;
      if (r && r != decision_reason)
        r->reason = true;
    }
  }
  void unprotect_reasons () {
    for (Clause *c : clauses)
      c->reason = false;
  }
  void mark_satisfied_garbage () {
    for (Clause *c : clauses) {
      if (c->garbage)
        continue;
      bool sat = false;
      for (int lit : c->lits)
        if (val (lit) > 0 && var (lit).level == 0) {
          sat = true;
          break;
        }
      if (sat)
        c->garbage = true;
    }
  }
  void reduce () {
    reductions++;
    protect_reasons ();

    const int tier1 = OPT_reducetier1glue;
    const int tier2 = std::max (tier1, OPT_reducetier2glue);
    vector<Clause *> stack;
    for (Clause *c : clauses) {
      if (!c->redundant)
        continue;
      if (c->garbage)
        continue;
      if (c->reason)
        continue;
      const int used = c->used;
      if (used)
        c->used--;
      if (c->glue <= tier1 && used)
        continue;
      if (c->glue <= tier2 && used >= max_used_val () - 1)
        continue;
      stack.push_back (c);
    }
    std::stable_sort (stack.begin (), stack.end (), [] (Clause *c, Clause *d) {
      if (c->glue > d->glue)
        return true;
      if (c->glue < d->glue)
        return false;
      return c->size > d->size;
    });
    size_t target = (size_t) (1e-2 * OPT_reducetarget * stack.size ());
    if (target > stack.size ())
      target = stack.size ();
    for (size_t i = 0; i < target; i++)
      stack[i]->garbage = true;

    garbage_collect ();
    unprotect_reasons ();

    int64_t delta = OPT_reduceint;
    double factor = std::sqrt ((double) conflicts);
    if (factor < 1)
      factor = 1;
    delta = (int64_t) (delta * factor);
    if (delta < 1)
      delta = 1;
    lim_reduce = conflicts + delta;
    TR ("REDUCE\n");
  }

  void garbage_collect () {
    // unwatch and delete garbage clauses; keep the rest
    vector<Clause *> keep_clauses;
    for (Clause *c : clauses) {
      if (c->garbage) {
        if (c->size >= 2)
          unwatch_clause (c);
      } else {
        keep_clauses.push_back (c);
      }
    }
    for (Clause *c : clauses)
      if (c->garbage)
        delete c;
    clauses.swap (keep_clauses);
  }

  // ---- decide (decide.cpp / phases.cpp) ----
  int next_decision_variable_on_queue () {
    int res = q_unassigned;
    while (vals[res])
      res = links[res].prev;
    if (res != q_unassigned)
      update_queue_unassigned (res);
    return res;
  }
  int next_decision_variable_with_best_score () {
    int res = 0;
    for (;;) {
      res = (int) heap_front ();
      if (!vals[res])
        break;
      heap_pop ();
    }
    return res;
  }
  int next_decision_variable () {
    if (use_scores ())
      return next_decision_variable_with_best_score ();
    else
      return next_decision_variable_on_queue ();
  }
  int decide_phase (int idx, bool target) {
    const int initial_phase = OPT_phase ? 1 : -1;
    int phase = 0;
    if (!phase && target)
      phase = phase_target[idx];
    if (!phase)
      phase = phase_saved[idx];
    if (!phase)
      phase = initial_phase;
    return phase * idx;
  }
  bool satisfied () {
    return num_assigned == (size_t) max_var;
  }
  int decide () {
    int idx = next_decision_variable ();
    const bool target = (OPT_target > 1 || (stable && OPT_target));
    int decision = decide_phase (idx, target);
    new_trail_level (decision);
    TR ("DECIDE %d\n", decision);
    assign (decision, decision_reason, 'D');
    return 0;
  }

  // ---- add clause (root-level simplification) ----
  bool add_clause (const vector<int> &raw) {
    // dedup + tautology + drop root-false; enqueue units
    vector<int> ps;
    bool taut = false;
    for (int lit : raw) {
      ensure_var (vidx (lit));
      const signed char v = val (lit);
      if (v > 0 && var (lit).level == 0) {
        taut = true;
        break; // already satisfied at root
      }
      if (v < 0 && var (lit).level == 0)
        continue; // drop root-false
      // dedup / tautology within clause
      bool dup = false;
      for (int q : ps) {
        if (q == lit) {
          dup = true;
          break;
        }
        if (q == -lit) {
          taut = true;
          break;
        }
      }
      if (taut)
        break;
      if (!dup)
        ps.push_back (lit);
    }
    if (taut)
      return true;
    if (ps.empty ()) {
      unsat = true;
      return false;
    }
    if (ps.size () == 1) {
      assign (ps[0], 0, 'U');
      return propagate ();
    }
    new_clause (ps, false, 0);
    return true;
  }

  // ---- solve loop (internal.cpp cdcl_loop, inprocessing disabled) ----
  int solve () {
    if (unsat) {
      TR ("RESULT UNSAT\n");
      return 20;
    }
    init_averages ();
    averages_swapped = false;
    // initial root propagation
    if (!propagate ()) {
      analyze ();
      if (unsat) {
        TR ("RESULT UNSAT\n");
        return 20;
      }
    }
    lim_stabilize = OPT_stabilizeinit;

    int res = 0;
    while (!res) {
      if (unsat)
        res = 20;
      else if (!propagate ())
        analyze ();
      else if (iterating)
        iterating = false;
      else if (satisfied ())
        res = 10;
      else if (restarting ())
        restart ();
      else if (reducing ())
        reduce ();
      else
        decide ();
    }

    if (res == 10) {
      capture_model ();
      TR ("RESULT SAT\n");
    } else {
      TR ("RESULT UNSAT\n");
    }
    return res;
  }

  void capture_model () {
    model.assign (max_var + 1, 0);
    for (int i = 1; i <= max_var; i++)
      model[i] = vals[i];
  }
};

// =============================================================================
// DIMACS reader (plain stdio, like minisat_trace.cc).
// =============================================================================
static bool read_dimacs (const char *path, Solver &s) {
  FILE *f = fopen (path, "r");
  if (!f) {
    fprintf (stderr, "cannot open %s\n", path);
    return false;
  }
  int ch;
  int declared_vars = 0, declared_clauses = 0;
  (void) declared_clauses;
  vector<int> clause;
  for (;;) {
    ch = getc (f);
    if (ch == EOF)
      break;
    if (ch == 'c') {
      while ((ch = getc (f)) != '\n' && ch != EOF)
        ;
      continue;
    }
    if (ch == 'p') {
      // p cnf V C
      if (fscanf (f, " cnf %d %d", &declared_vars, &declared_clauses) != 2) {
        fprintf (stderr, "bad header\n");
        fclose (f);
        return false;
      }
      continue;
    }
    if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t')
      continue;
    ungetc (ch, f);
    int lit;
    if (fscanf (f, "%d", &lit) != 1)
      break;
    if (lit == 0) {
      if (!s.add_clause (clause) && s.unsat) {
        // formula already UNSAT at parse
      }
      clause.clear ();
    } else {
      clause.push_back (lit);
    }
  }
  if (!clause.empty ())
    s.add_clause (clause);
  fclose (f);
  return true;
}

int main (int argc, char **argv) {
  trace_init ();
  if (argc < 2) {
    fprintf (stderr, "usage: %s <file.cnf>\n", argv[0]);
    return 1;
  }
  Solver s;
  if (!read_dimacs (argv[1], s))
    return 1;
  int res = s.solve ();
  if (res == 10) {
    printf ("s SATISFIABLE\n");
  } else {
    printf ("s UNSATISFIABLE\n");
  }
  return res == 10 ? 10 : 20;
}
