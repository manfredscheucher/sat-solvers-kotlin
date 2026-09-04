/*********************************************************************[minisat_trace.cc]***

  INSTRUMENTED, SELF-CONTAINED reference of MiniSat's bare core CDCL solver.

  Copyright (c) 2003-2010, Niklas Een, Niklas Sorensson (MIT). The algorithm here is
  transcribed 1:1 from the upstream MiniSat core (minisat/core/Solver.{cc,h},
  SolverTypes.h, mtl/Heap.h), kept verbatim as minisat_orig.cc. The ONLY differences
  vs upstream are:
    * everything the bare core needs is inlined into one file (no Options/System/zlib),
      so it builds with a single `clang++ -O2 minisat_trace.cc`;
    * a plain stdio DIMACS reader replaces the gzip StreamBuffer;
    * trace(...) prints one line per decision-relevant event, gated by the LSTRACE env
      var so normal runs stay clean. No algorithm change.

  We port the BARE Solver (core CDCL): watched literals, double-precision VSIDS with an
  activity heap, 1-UIP analyze with deep conflict-clause minimization (ccmin_mode=2),
  Luby restarts, activity-based reduceDB, full phase saving (phase_saving=2). No
  SimpSolver / preprocessing, no assumptions, no random decisions (random_var_freq=0),
  no garbage collection needed (we keep the RegionAllocator-style arena but never gc,
  matching that these instances stay well under the gc fraction; the Kotlin port mirrors
  this exactly with an IntArray arena).

  Shared trace vocabulary (stable; the Kotlin port must match it line-for-line at L1,
  or the decision/event sequence at L2):
    DECIDE <lit>                 a branching decision was enqueued (signed DIMACS lit)
    ASSIGN <lit> reason=<D|F|C>  a literal became true. D=decision, F=unit at level 0
                                 (root/forced), C=implied by a clause during search.
    CONFLICT                     propagate returned a conflicting clause
    RESTART                      a (Luby) restart fired
    REDUCE                       reduceDB ran
    RESULT <SAT|UNSAT>           final verdict
  Literals are the signed DIMACS literal actually made true (internal var v -> v+1).

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
static void trace_init() { TRACE_ON = getenv("LSTRACE") != NULL; }
#define TR(...) do { if (TRACE_ON) { printf(__VA_ARGS__); } } while (0)

// =============================================================================
// Literals / lbool  (SolverTypes.h)
// =============================================================================
typedef int Var;
static const Var var_Undef = -1;

struct Lit { int x; };
static inline Lit  mkLit(Var v, bool sign) { Lit p; p.x = v + v + (int)sign; return p; }
static inline Lit  neg(Lit p)              { Lit q; q.x = p.x ^ 1; return q; }
static inline bool sign(Lit p)             { return p.x & 1; }
static inline int  var(Lit p)              { return p.x >> 1; }
static inline bool eq(Lit a, Lit b)        { return a.x == b.x; }
static const Lit lit_Undef = { -2 };

// lbool encoded as MiniSat does: 0 = True, 1 = False, 2 = Undef.
typedef uint8_t lbool;
static const lbool l_True  = 0;
static const lbool l_False = 1;
static const lbool l_Undef = 2;
// value(lit) = assigns[var] ^ sign, with lbool XOR bool = flip bit 0 only when defined.
static inline lbool lbool_xor(lbool v, bool b) { return (lbool)(v ^ (uint8_t)b); }

// signed DIMACS literal for a true internal lit p (var v -> DIMACS v+1)
static inline int dimacsOf(Lit p) { return sign(p) ? -(var(p) + 1) : (var(p) + 1); }

// =============================================================================
// Clause arena (RegionAllocator<uint32_t> + Clause, SolverTypes.h)
// Layout per clause at ref r (uint32 words):
//   [r]   header  : bit0 learnt, then size in high bits (we keep it simple + faithful)
//   [r+1..r+size] literals (Lit.x)
//   [r+1+size]    extra: activity (float, reinterpreted) -- present for all clauses here
// We reproduce MiniSat's semantics: learnt clauses carry a float activity; original
// clauses also allocate the extra word (extra_clause_field stays false in core, but
// original clauses have has_extra only if extra_clause_field; to keep reduceDB's
// activity() valid we only ever read activity() on learnts, exactly like upstream).
// =============================================================================
typedef uint32_t CRef;
static const CRef CRef_Undef = 0xFFFFFFFFu;

struct Clause {
    // We store header fields explicitly for clarity; memory-faithfulness to the byte
    // layout is not needed for trace equality, only the *values*.
    uint32_t mark;      // 0 normal, 1 removed
    bool     learnt;
    int      sz;
    float    act;       // clause activity (learnts only, like upstream)
    vector<Lit> lits;
    Clause() : mark(0), learnt(false), sz(0), act(0) {}
    int  size() const { return sz; }
    Lit  operator[](int i) const { return lits[i]; }
    Lit& operator[](int i)       { return lits[i]; }
};

struct ClauseAllocator {
    vector<Clause> store;                 // index == CRef
    CRef alloc(const vector<Lit>& ps, bool learnt) {
        Clause c; c.learnt = learnt; c.sz = (int)ps.size(); c.act = 0;
        c.lits = ps;
        store.push_back(c);
        return (CRef)(store.size() - 1);
    }
    Clause&       operator[](CRef r)       { return store[r]; }
    const Clause& operator[](CRef r) const { return store[r]; }
};

// =============================================================================
// Watcher
// =============================================================================
struct Watcher {
    CRef cref;
    Lit  blocker;
    Watcher() : cref(0), blocker(Lit{0}) {}   // needed for std::vector::resize
    Watcher(CRef cr, Lit p) : cref(cr), blocker(p) {}
};

// =============================================================================
// Solver
// =============================================================================
struct VarData { CRef reason; int level; };

struct Solver {
    // options (defaults exactly as upstream core)
    double var_decay      = 0.95;
    double clause_decay   = 0.999;
    int    ccmin_mode     = 2;
    int    phase_saving   = 2;
    bool   luby_restart   = true;
    int    restart_first  = 100;
    double restart_inc    = 2;
    double learntsize_factor = (double)1/(double)3;
    double learntsize_inc    = 1.1;
    int    learntsize_adjust_start_confl = 100;
    double learntsize_adjust_inc         = 1.5;
    int    min_learnts_lim = 0;

    // state
    ClauseAllocator ca;
    vector<CRef> clauses, learnts;
    vector<Lit>  trail;
    vector<int>  trail_lim;
    vector<double> activity;
    vector<lbool>  assigns;
    vector<char>   polarity;
    vector<char>   decision;
    vector<VarData> vardata;
    vector<vector<Watcher>> watches;   // indexed by Lit.x

    bool   ok = true;
    double cla_inc = 1;
    double var_inc = 1;
    int    qhead = 0;
    int    next_var = 0;
    uint64_t conflicts = 0;
    uint64_t dec_vars = 0;

    double max_learnts = 0;
    double learntsize_adjust_confl = 0;
    int    learntsize_adjust_cnt = 0;

    // decision heap (Heap<Var,VarOrderLt> with lt = activity[x] > activity[y])
    vector<int> heap;      // heap of Vars
    vector<int> hindices;  // var -> position in heap, -1 if not in heap

    // temporaries
    vector<char> seen;
    vector<Lit>  analyze_toclear;
    vector<Lit>  add_tmp;

    // ---- lbool / value ----
    lbool value(Var x) const { return assigns[x]; }
    lbool value(Lit p) const { return lbool_xor(assigns[var(p)], sign(p)); }

    int  nVars() const { return next_var; }
    int  nAssigns() const { return (int)trail.size(); }
    int  decisionLevel() const { return (int)trail_lim.size(); }
    CRef reason(Var x) const { return vardata[x].reason; }
    int  level (Var x) const { return vardata[x].level; }

    // ---- heap ops (Heap.h) ----
    static int hleft (int i){ return i*2+1; }
    static int hright(int i){ return (i+1)*2; }
    static int hparent(int i){ return (i-1)>>1; }
    bool lt(int xv, int yv) const { return activity[xv] > activity[yv]; } // VarOrderLt
    bool inHeap(int k) const { return k < (int)hindices.size() && hindices[k] >= 0; }
    void percolateUp(int i) {
        int x = heap[i];
        int p = hparent(i);
        while (i != 0 && lt(x, heap[p])) {
            heap[i] = heap[p]; hindices[heap[p]] = i;
            i = p; p = hparent(p);
        }
        heap[i] = x; hindices[x] = i;
    }
    void percolateDown(int i) {
        int x = heap[i];
        while (hleft(i) < (int)heap.size()) {
            int child = hright(i) < (int)heap.size() && lt(heap[hright(i)], heap[hleft(i)]) ? hright(i) : hleft(i);
            if (!lt(heap[child], x)) break;
            heap[i] = heap[child]; hindices[heap[i]] = i;
            i = child;
        }
        heap[i] = x; hindices[x] = i;
    }
    void heapDecrease(int k) { percolateUp(hindices[k]); }   // activity increased
    void heapInsert(int k) {
        if ((int)hindices.size() <= k) hindices.resize(k+1, -1);
        hindices[k] = (int)heap.size();
        heap.push_back(k);
        percolateUp(hindices[k]);
    }
    int heapRemoveMin() {
        int x = heap[0];
        heap[0] = heap.back(); hindices[heap[0]] = 0;
        hindices[x] = -1;
        heap.pop_back();
        if (heap.size() > 1) percolateDown(0);
        return x;
    }
    bool heapEmpty() const { return heap.empty(); }

    void insertVarOrder(Var x) { if (!inHeap(x) && decision[x]) heapInsert(x); }

    // ---- activity ----
    void varDecayActivity() { var_inc *= (1 / var_decay); }
    void varBumpActivity(Var v, double inc) {
        if ((activity[v] += inc) > 1e100) {
            for (int i = 0; i < nVars(); i++) activity[i] *= 1e-100;
            var_inc *= 1e-100;
        }
        if (inHeap(v)) heapDecrease(v);
    }
    void varBumpActivity(Var v) { varBumpActivity(v, var_inc); }
    void claDecayActivity() { cla_inc *= (1 / clause_decay); }
    void claBumpActivity(Clause& c) {
        if ((c.act += (float)cla_inc) > 1e20f) {
            for (size_t i = 0; i < learnts.size(); i++) ca[learnts[i]].act *= 1e-20f;
            cla_inc *= 1e-20;
        }
    }

    // ---- new variable ----
    Var newVar(bool dvar = true) {
        Var v = next_var++;
        watches.resize((v+1)*2);
        assigns.push_back(l_Undef);
        vardata.push_back({CRef_Undef, 0});
        activity.push_back(0);
        seen.push_back(0);
        polarity.push_back(1);      // preferred polarity true -> mkLit(v,true) -> negative lit
        decision.push_back(0);
        setDecisionVar(v, dvar);
        return v;
    }
    void setDecisionVar(Var v, bool b) {
        if      (b && !decision[v]) dec_vars++;
        else if (!b && decision[v]) dec_vars--;
        decision[v] = b;
        insertVarOrder(v);
    }

    // ---- clause attach ----
    void attachClause(CRef cr) {
        const Clause& c = ca[cr];
        watches[neg(c[0]).x].push_back(Watcher(cr, c[1]));
        watches[neg(c[1]).x].push_back(Watcher(cr, c[0]));
    }
    void detachClause(CRef cr) {
        const Clause& c = ca[cr];
        auto rm = [&](vector<Watcher>& ws, CRef target){
            for (size_t i=0;i<ws.size();i++) if (ws[i].cref==target){ ws.erase(ws.begin()+i); break; }
        };
        rm(watches[neg(c[0]).x], cr);
        rm(watches[neg(c[1]).x], cr);
    }
    void removeClause(CRef cr) {
        detachClause(cr);
        if (locked(cr)) vardata[var(ca[cr][0])].reason = CRef_Undef;
        ca[cr].mark = 1;
    }
    // A clause is locked iff it is the reason for its (true) first literal's assignment.
    bool locked(CRef cr) const {
        Lit c0 = ca[cr][0];
        return value(c0) == l_True && reason(var(c0)) != CRef_Undef && reason(var(c0)) == cr;
    }
    bool satisfied(const Clause& c) const {
        for (int i=0;i<c.size();i++) if (value(c[i])==l_True) return true;
        return false;
    }

    // ---- enqueue ----
    void uncheckedEnqueue(Lit p, CRef from, char rc) {
        assigns[var(p)] = lbool_xor(l_True, sign(p)); // = !sign(p) as lbool(!sign)
        vardata[var(p)] = { from, decisionLevel() };
        trail.push_back(p);
        TR("ASSIGN %d reason=%c\n", dimacsOf(p), rc);
    }

    // ---- backtrack ----
    void cancelUntil(int lvl) {
        if (decisionLevel() > lvl) {
            for (int c = (int)trail.size()-1; c >= trail_lim[lvl]; c--) {
                Var x = var(trail[c]);
                assigns[x] = l_Undef;
                if (phase_saving > 1 || (phase_saving == 1 && c > trail_lim.back()))
                    polarity[x] = sign(trail[c]);
                insertVarOrder(x);
            }
            qhead = trail_lim[lvl];
            trail.resize(trail_lim[lvl]);
            trail_lim.resize(lvl);
        }
    }
    void newDecisionLevel() { trail_lim.push_back((int)trail.size()); }

    // ---- pick branch ----
    Lit pickBranchLit() {
        Var next = var_Undef;
        while (next == var_Undef || value(next) != l_Undef || !decision[next])
            if (heapEmpty()) { next = var_Undef; break; }
            else next = heapRemoveMin();
        if (next == var_Undef) return lit_Undef;
        return mkLit(next, polarity[next]);
    }

    // ---- addClause ----
    bool addClause(vector<Lit>& ps) {
        assert(decisionLevel() == 0);
        if (!ok) return false;
        std::sort(ps.begin(), ps.end(), [](Lit a, Lit b){ return a.x < b.x; });
        Lit p; int i, j;
        p = lit_Undef;
        for (i = j = 0; i < (int)ps.size(); i++)
            if (value(ps[i]) == l_True || eq(ps[i], neg(p)))
                return true;
            else if (value(ps[i]) != l_False && !eq(ps[i], p))
                ps[j++] = p = ps[i];
        ps.resize(j);

        if (ps.size() == 0)
            return ok = false;
        else if (ps.size() == 1) {
            uncheckedEnqueue(ps[0], CRef_Undef, 'F');
            return ok = (propagate() == CRef_Undef);
        } else {
            CRef cr = ca.alloc(ps, false);
            clauses.push_back(cr);
            attachClause(cr);
        }
        return true;
    }

    // ---- propagate ----
    CRef propagate() {
        CRef confl = CRef_Undef;
        while (qhead < (int)trail.size()) {
            Lit p = trail[qhead++];
            vector<Watcher>& ws = watches[p.x];
            size_t i, j; size_t end = ws.size();
            for (i = j = 0; i != end; ) {
                Lit blocker = ws[i].blocker;
                if (value(blocker) == l_True) { ws[j++] = ws[i++]; continue; }

                CRef cr = ws[i].cref;
                Clause& c = ca[cr];
                Lit false_lit = neg(p);
                if (eq(c[0], false_lit)) { c[0] = c[1]; c[1] = false_lit; }
                i++;

                Lit first = c[0];
                Watcher w(cr, first);
                if (!eq(first, blocker) && value(first) == l_True) { ws[j++] = w; continue; }

                bool found = false;
                for (int k = 2; k < c.size(); k++)
                    if (value(c[k]) != l_False) {
                        c[1] = c[k]; c[k] = false_lit;
                        watches[neg(c[1]).x].push_back(w);
                        found = true; break;
                    }
                if (found) continue;

                ws[j++] = w;
                if (value(first) == l_False) {
                    confl = cr;
                    qhead = (int)trail.size();
                    while (i < end) ws[j++] = ws[i++];
                } else {
                    uncheckedEnqueue(first, cr, 'C');
                }
            }
            ws.resize(j);
        }
        return confl;
    }

    // ---- litRedundant (ccmin_mode 2) ----
    struct ShrinkStackElem { uint32_t i; Lit l; };
    vector<ShrinkStackElem> analyze_stack;
    enum { seen_undef=0, seen_source=1, seen_removable=2, seen_failed=3 };
    bool litRedundant(Lit p) {
        Clause* c = &ca[reason(var(p))];
        analyze_stack.clear();
        for (uint32_t i = 1; ; i++) {
            if ((int)i < c->size()) {
                Lit l = (*c)[i];
                if (level(var(l)) == 0 || seen[var(l)] == seen_source || seen[var(l)] == seen_removable)
                    continue;
                if (reason(var(l)) == CRef_Undef || seen[var(l)] == seen_failed) {
                    analyze_stack.push_back({0, p});
                    for (size_t k = 0; k < analyze_stack.size(); k++)
                        if (seen[var(analyze_stack[k].l)] == seen_undef) {
                            seen[var(analyze_stack[k].l)] = seen_failed;
                            analyze_toclear.push_back(analyze_stack[k].l);
                        }
                    return false;
                }
                analyze_stack.push_back({i, p});
                i = 0; p = l; c = &ca[reason(var(p))];
            } else {
                if (seen[var(p)] == seen_undef) {
                    seen[var(p)] = seen_removable;
                    analyze_toclear.push_back(p);
                }
                if (analyze_stack.size() == 0) break;
                i = analyze_stack.back().i;
                p = analyze_stack.back().l;
                c = &ca[reason(var(p))];
                analyze_stack.pop_back();
            }
        }
        return true;
    }

    // ---- analyze ----
    void analyze(CRef confl, vector<Lit>& out_learnt, int& out_btlevel) {
        int pathC = 0;
        Lit p = lit_Undef;
        out_learnt.push_back(lit_Undef); // room for asserting lit
        int index = (int)trail.size() - 1;

        do {
            Clause& c = ca[confl];
            if (c.learnt) claBumpActivity(c);
            for (int jj = (eq(p, lit_Undef)) ? 0 : 1; jj < c.size(); jj++) {
                Lit q = c[jj];
                if (!seen[var(q)] && level(var(q)) > 0) {
                    varBumpActivity(var(q));
                    seen[var(q)] = 1;
                    if (level(var(q)) >= decisionLevel()) pathC++;
                    else out_learnt.push_back(q);
                }
            }
            while (!seen[var(trail[index--])]);
            p = trail[index+1];
            confl = reason(var(p));
            seen[var(p)] = 0;
            pathC--;
        } while (pathC > 0);
        out_learnt[0] = neg(p);

        // simplify
        int i, j;
        analyze_toclear = out_learnt;
        if (ccmin_mode == 2) {
            for (i = j = 1; i < (int)out_learnt.size(); i++)
                if (reason(var(out_learnt[i])) == CRef_Undef || !litRedundant(out_learnt[i]))
                    out_learnt[j++] = out_learnt[i];
        } else if (ccmin_mode == 1) {
            for (i = j = 1; i < (int)out_learnt.size(); i++) {
                Var x = var(out_learnt[i]);
                if (reason(x) == CRef_Undef) out_learnt[j++] = out_learnt[i];
                else {
                    Clause& c = ca[reason(x)];
                    for (int k = 1; k < c.size(); k++)
                        if (!seen[var(c[k])] && level(var(c[k])) > 0) { out_learnt[j++] = out_learnt[i]; break; }
                }
            }
        } else i = j = (int)out_learnt.size();
        out_learnt.resize(out_learnt.size() - (i - j));

        // backtrack level
        if (out_learnt.size() == 1) out_btlevel = 0;
        else {
            int max_i = 1;
            for (int k = 2; k < (int)out_learnt.size(); k++)
                if (level(var(out_learnt[k])) > level(var(out_learnt[max_i]))) max_i = k;
            Lit q = out_learnt[max_i];
            out_learnt[max_i] = out_learnt[1];
            out_learnt[1] = q;
            out_btlevel = level(var(q));
        }
        for (size_t k = 0; k < analyze_toclear.size(); k++) seen[var(analyze_toclear[k])] = 0;
    }

    // ---- reduceDB ----
    void reduceDB() {
        TR("REDUCE\n");
        double extra_lim = cla_inc / learnts.size();
        // reduceDB_lt: x precedes y iff size(x)>2 && (size(y)==2 || act(x)<act(y)).
        // A stable insertion sort with this exact predicate, matched byte-for-byte by the
        // Kotlin port, so both order incomparable clauses identically.
        auto reduceLt = [&](CRef x, CRef y){
            return ca[x].size() > 2 && (ca[y].size() == 2 || ca[x].act < ca[y].act);
        };
        for (size_t a = 1; a < learnts.size(); a++) {
            CRef key = learnts[a];
            long b = (long)a - 1;
            while (b >= 0 && reduceLt(key, learnts[b])) { learnts[b+1] = learnts[b]; b--; }
            learnts[b+1] = key;
        }
        int i, j;
        for (i = j = 0; i < (int)learnts.size(); i++) {
            Clause& c = ca[learnts[i]];
            if (c.size() > 2 && !locked(learnts[i]) && (i < (int)learnts.size()/2 || c.act < extra_lim))
                removeClause(learnts[i]);
            else
                learnts[j++] = learnts[i];
        }
        learnts.resize(j);
    }

    // ---- luby ----
    static double luby(double y, int x) {
        int size, seq;
        for (size = 1, seq = 0; size < x+1; seq++, size = 2*size+1);
        while (size-1 != x) { size = (size-1)>>1; seq--; x = x % size; }
        return pow(y, seq);
    }

    // ---- search ----
    // returns l_True / l_False / l_Undef
    // Assumptions: signed-DIMACS literals forced as decisions before free search, mirrored
    // 1:1 by the Kotlin port (Solver::search consumes them at the front of the trail). Empty
    // for the plain shadow runs; set from a file in main() for the assumption shadow runs.
    vector<Lit> assumptions;

    lbool search(int nof_conflicts) {
        int backtrack_level;
        int conflictC = 0;
        vector<Lit> learnt_clause;
        for (;;) {
            CRef confl = propagate();
            if (confl != CRef_Undef) {
                TR("CONFLICT\n");
                conflicts++; conflictC++;
                if (decisionLevel() == 0) return l_False;
                learnt_clause.clear();
                analyze(confl, learnt_clause, backtrack_level);
                cancelUntil(backtrack_level);
                if (learnt_clause.size() == 1) {
                    uncheckedEnqueue(learnt_clause[0], CRef_Undef, 'C');
                } else {
                    CRef cr = ca.alloc(learnt_clause, true);
                    learnts.push_back(cr);
                    attachClause(cr);
                    claBumpActivity(ca[cr]);
                    uncheckedEnqueue(learnt_clause[0], cr, 'C');
                }
                varDecayActivity();
                claDecayActivity();
                if (--learntsize_adjust_cnt == 0) {
                    learntsize_adjust_confl *= learntsize_adjust_inc;
                    learntsize_adjust_cnt = (int)learntsize_adjust_confl;
                    max_learnts *= learntsize_inc;
                }
            } else {
                if (nof_conflicts >= 0 && conflictC >= nof_conflicts) {
                    cancelUntil(0);
                    return l_Undef;
                }
                if (decisionLevel() == 0 && !simplify()) return l_False;
                if ((int)learnts.size() - nAssigns() >= (int)max_learnts) reduceDB();

                // Consume assumptions first (upstream Solver::search): each becomes a forced
                // decision on its own level; an already-true one gets a dummy level, an
                // already-false one makes this solve UNSAT under the assumptions.
                Lit next = lit_Undef;
                while (decisionLevel() < (int)assumptions.size()) {
                    Lit p = assumptions[decisionLevel()];
                    if (value(p) == l_True) {
                        newDecisionLevel();
                    } else if (value(p) == l_False) {
                        return l_False;
                    } else {
                        next = p;
                        break;
                    }
                }

                if (eq(next, lit_Undef)) {
                    next = pickBranchLit();
                    if (eq(next, lit_Undef)) return l_True;
                }
                newDecisionLevel();
                TR("DECIDE %d\n", dimacsOf(next));
                uncheckedEnqueue(next, CRef_Undef, 'D');
            }
        }
    }

    // ---- simplify (top-level satisfied-clause removal) ----
    int simpDB_assigns = -1;
    bool simplify() {
        assert(decisionLevel() == 0);
        if (!ok || propagate() != CRef_Undef) return ok = false;
        if (nAssigns() == simpDB_assigns) return true;
        removeSatisfied(learnts);
        removeSatisfied(clauses);
        rebuildOrderHeap();
        simpDB_assigns = nAssigns();
        return true;
    }
    void removeSatisfied(vector<CRef>& cs) {
        int i, j;
        for (i = j = 0; i < (int)cs.size(); i++) {
            Clause& c = ca[cs[i]];
            if (satisfied(c)) removeClause(cs[i]);
            else {
                for (int k = 2; k < c.size(); k++)
                    if (value(c[k]) == l_False) { c[k--] = c[c.size()-1]; c.lits.pop_back(); c.sz--; }
                cs[j++] = cs[i];
            }
        }
        cs.resize(j);
    }
    void rebuildOrderHeap() {
        vector<int> vs;
        for (Var v = 0; v < nVars(); v++)
            if (decision[v] && value(v) == l_Undef) vs.push_back(v);
        // Heap::build
        for (size_t i = 0; i < heap.size(); i++) hindices[heap[i]] = -1;
        heap.clear();
        if ((int)hindices.size() < nVars()) hindices.resize(nVars(), -1);
        for (size_t i = 0; i < vs.size(); i++) { hindices[vs[i]] = (int)i; heap.push_back(vs[i]); }
        for (int i = (int)heap.size()/2 - 1; i >= 0; i--) percolateDown(i);
    }

    // ---- solve_ ----
    int solve_() { // returns 0 UNSAT, 1 SAT
        if (!ok) { TR("RESULT UNSAT\n"); return 0; }
        max_learnts = nClauses() * learntsize_factor;
        if (max_learnts < min_learnts_lim) max_learnts = min_learnts_lim;
        learntsize_adjust_confl = learntsize_adjust_start_confl;
        learntsize_adjust_cnt = (int)learntsize_adjust_confl;
        lbool status = l_Undef;
        int curr_restarts = 0;
        while (status == l_Undef) {
            double rest_base = luby_restart ? luby(restart_inc, curr_restarts) : pow(restart_inc, curr_restarts);
            int budget = (int)(rest_base * restart_first);
            if (curr_restarts > 0) TR("RESTART\n");
            status = search(budget);
            curr_restarts++;
        }
        // Capture the model BEFORE cancelUntil(0), exactly as MiniSat's solve_ does.
        if (status == l_True) captureModel();
        cancelUntil(0);
        if (status == l_True) { TR("RESULT SAT\n"); return 1; }
        else { TR("RESULT UNSAT\n"); return 0; }
    }

    int nClauses() const { return (int)clauses.size(); }

    // model access
    vector<lbool> model;
    void captureModel() {
        model.assign(nVars(), l_Undef);
        for (int i = 0; i < nVars(); i++) model[i] = value(i);
    }
};

// =============================================================================
// Plain DIMACS reader (no zlib). Mirrors Dimacs.h: vars created lazily by abs(lit)-1.
// =============================================================================
static bool parseDimacs(Solver& S, const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) { fprintf(stderr, "cannot open %s\n", path); return false; }
    vector<Lit> lits;
    int ch;
    auto ensureVars = [&](int v){ while (v >= S.nVars()) S.newVar(); };
    // simple tokenizer
    int c;
    while ((c = fgetc(f)) != EOF) {
        if (c == 'c' || c == 'p') { while ((ch=fgetc(f))!='\n' && ch!=EOF); continue; }
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
        ungetc(c, f);
        int lit;
        if (fscanf(f, " %d", &lit) != 1) break;
        if (lit == 0) {
            S.addClause(lits);
            lits.clear();
        } else {
            int v = abs(lit) - 1;
            ensureVars(v);
            lits.push_back(mkLit(v, lit < 0));
        }
    }
    fclose(f);
    return true;
}

int main(int argc, char** argv) {
    trace_init();
    if (argc <= 1) { printf("no input file provided\n"); return 0; }
    Solver S;
    if (!parseDimacs(S, argv[1])) return 1;
    // Optional argv[2]: a file of signed-DIMACS assumption literals (whitespace-separated).
    // They are forced as decisions before free search, exactly as the Kotlin solve(assumptions).
    if (argc > 2) {
        FILE* af = fopen(argv[2], "r");
        if (!af) { printf("cannot open assumptions file %s\n", argv[2]); return 1; }
        int dl;
        while (fscanf(af, "%d", &dl) == 1) {
            if (dl == 0) continue;
            int v = abs(dl) - 1;
            while (v >= S.nVars()) S.newVar();
            S.assumptions.push_back(mkLit(v, dl < 0));
        }
        fclose(af);
    }
    int r = S.solve_();
    if (r == 1) { S.captureModel(); printf("s SATISFIABLE\n"); }
    else        { printf("s UNSATISFIABLE\n"); }
    return 0;
}
