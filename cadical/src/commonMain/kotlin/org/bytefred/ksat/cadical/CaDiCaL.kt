package org.bytefred.ksat.cadical

import org.bytefred.ksat.SatResult
import org.bytefred.ksat.SatSolver
import org.bytefred.ksat.Traceable

/**
 * Faithful Kotlin port of the CORE CDCL solver of CaDiCaL (MIT, (c) 2016-2024 Armin Biere
 * and the CaDiCaL authors), with ALL inprocessing disabled -> plain, deterministic,
 * trace-matchable CDCL. See ../shadow/cadical-c/cadical_trace.cc for the self-contained
 * instrumented C reference this is transcribed from 1:1.
 *
 * What is ported (the CDCL core, exactly as the C reference documents):
 *   - watched literals with blocking literals and Ian Gent's saved-position search
 *     (propagate.cpp);
 *   - conflict analysis via CaDiCaL's 'open'-counter 1st-UIP loop over the trail
 *     (analyze.cpp), glue/LBD = number of distinct decision levels touched - 1;
 *   - recursive conflict-clause minimization with poison/removable/keep flags
 *     (minimize.cpp, opts.minimize=1);
 *   - EVSIDS variable scores in a binary max-heap used in STABLE mode, plus the VMTF
 *     'bumped' decision queue used in FOCUSED mode (score.cpp/queue.hpp);
 *   - ADAM-style bias-corrected exponential moving averages of the glue (ema.cpp),
 *     driving Glucose-style restarts; stabilizing phases toggle stable/focused with
 *     reluctant (Luby) doubling restarts in stable mode (restart.cpp);
 *   - LBD-tiered reduce keeping tier1 (glue<=2) and recently-used tier2 (glue<=6),
 *     sorting the rest by (glue,size) and dropping a reducetarget fraction (reduce.cpp);
 *   - phase saving with target/best phases updated on backtrack (backtrack.cpp, phases.cpp).
 *
 * DISABLED (documented core configuration, so C and Kotlin match): chronological
 * backtracking, on-the-fly self-subsumption, clause shrinking beyond recursive minimize,
 * proofs, rephasing, and all inprocessing. See the C reference header for the full list.
 *
 * FLOATING POINT: the C reference stores EVSIDS scores (`stab`) and the glue EMAs as
 * `double`. Kotlin/JVM `Double` is IEEE-754 identical to C `double`, and every score/EMA
 * update here is transcribed in the same evaluation order, so L1 (byte-for-byte trace)
 * is expected to hold. Precision is exposed as a knob for the same reason MiniSat exposes
 * it: [ActivityPrecision.FLOAT64] matches the C reference (default), [ActivityPrecision.FLOAT32]
 * rounds every score write via toFloat().toDouble() (for a hypothetical float build / standalone
 * experiments). Not idiomatic Kotlin on purpose -- correspondence to the C beats idiom.
 */
enum class ActivityPrecision { FLOAT32, FLOAT64 }

class CaDiCaL(
    numVarsHint: Int,
    private val activityPrecision: ActivityPrecision = ActivityPrecision.FLOAT64,
    // TEST-ONLY knobs for standalone experiments: lower them to trigger REDUCE/RESTART/
    // stabilization on small CNFs. Defaults == upstream CaDiCaL. NOTE: the C reference
    // (cadical_trace.cc) has NO matching env counterpart -- its reduce/restart/stabilize
    // intervals are fixed `const`s and only LSTRACE is env-read -- so these knobs must be
    // left at their defaults for any run shadowed against the golden C traces (which they
    // are in ShadowTraceFilesTest). They exist purely for Kotlin-side exploration.
    reduceIntInit: Long = 25L,
    restartIntInit: Long = 2L,
    stabilizeInit: Long = 1000L,
) : SatSolver, Traceable {

    // ---- options (core configuration; see the C reference header) ----
    private companion object {
        const val OPT_score = true          // use EVSIDS in stable mode
        const val OPT_stabilize = true
        const val OPT_stabilizefactor = 200 // percent (unused with reluctant path)
        const val OPT_reluctant = true
        const val OPT_reluctantint = 1024L
        const val OPT_reluctantmax = 1048576L
        const val OPT_restart = true
        const val OPT_restartmarginfocused = 10 // percent
        const val OPT_restartmarginstable = 25  // percent
        const val OPT_reduce = true
        const val OPT_reducetarget = 75         // percent
        const val OPT_reducetier1glue = 2
        const val OPT_reducetier2glue = 6
        const val OPT_minimize = true
        const val OPT_minimizedepth = 1000
        const val OPT_bump = true
        const val OPT_scorefactor = 950         // per mille
        const val OPT_phase = true              // initial phase positive
        const val OPT_target = 1                // target phases (stable only)
        const val OPT_emagluefast = 33.0
        const val OPT_emaglueslow = 1e5
    }

    private val OPT_stabilizeinit: Long = stabilizeInit
    private val OPT_restartint: Long = restartIntInit
    private val OPT_reduceint: Long = reduceIntInit

    /** Round an EVSIDS score to the configured precision (C `double` vs experimental `float`). */
    private fun scoreRound(x: Double): Double =
        if (activityPrecision == ActivityPrecision.FLOAT32) x.toFloat().toDouble() else x

    override var numVars: Int = 0
        private set

    // ---- trace sink ----
    private var trace: ((String) -> Unit)? = null
    override fun setTraceSink(sink: ((String) -> Unit)?) { trace = sink }
    private inline fun tr(line: String) { trace?.invoke(line) }

    // =========================================================================
    // EMA (ema.cpp): ADAM-style bias-corrected exponential moving average.
    // =========================================================================
    private class Ema {
        var value = 0.0
        var biased = 0.0
        var alpha = 0.0
        var beta = 0.0
        var exp = 0.0

        constructor()
        constructor(a: Double) {
            alpha = a; beta = 1 - a; exp = if (a != 1.0) 1.0 else 0.0
        }

        fun update(y: Double) {
            val oldBiased = biased
            val delta = y - oldBiased
            val scaledDelta = alpha * delta
            val newBiased = oldBiased + scaledDelta
            biased = newBiased
            val oldExp = exp
            if (oldExp != 0.0) {
                val newExp = oldExp * beta
                exp = newExp
                val div = 1 - newExp
                value = newBiased / div
            } else {
                value = newBiased
            }
        }

        fun copyFrom(o: Ema) { value = o.value; biased = o.biased; alpha = o.alpha; beta = o.beta; exp = o.exp }
    }

    private fun initEma(window: Double): Ema = Ema(1.0 / window)

    // =========================================================================
    // Clauses. The C reference uses heap-allocated Clause* with pointer identity; we
    // mirror that with an object arena of parallel columns indexed by an Int handle
    // (a "clause ref" = index). Clause literals are the signed DIMACS lits, as in C.
    // A ref of -1 means "no clause"; DECISION is a sentinel for a decision reason.
    // =========================================================================
    private var cRedundant = BooleanArray(64)
    private var cGarbage = BooleanArray(64)
    private var cReason = BooleanArray(64)   // currently a reason clause (protect from reduce)
    private var cGlue = IntArray(64)
    private var cUsed = IntArray(64)
    private var cPos = IntArray(64)          // Gent saved search position
    private var cSize = IntArray(64)
    private var cLits = arrayOfNulls<IntArray>(64)
    private var cCount = 0

    private val CREF_NONE = -1
    private val CREF_DECISION = -2           // decision_reason sentinel

    private fun cEnsure(cap: Int) {
        if (cap <= cRedundant.size) return
        var n = cRedundant.size
        while (n < cap) n *= 2
        cRedundant = cRedundant.copyOf(n)
        cGarbage = cGarbage.copyOf(n)
        cReason = cReason.copyOf(n)
        cGlue = cGlue.copyOf(n)
        cUsed = cUsed.copyOf(n)
        cPos = cPos.copyOf(n)
        cSize = cSize.copyOf(n)
        cLits = cLits.copyOf(n)
    }

    private fun newClauseArena(lits: IntArray, redundant: Boolean, glue: Int): Int {
        cEnsure(cCount + 1)
        val cr = cCount++
        cRedundant[cr] = redundant
        cGarbage[cr] = false
        cReason[cr] = false
        cGlue[cr] = glue
        cUsed[cr] = if (redundant) 1 else 0
        cPos[cr] = 2
        cSize[cr] = lits.size
        cLits[cr] = lits.copyOf()
        return cr
    }

    private fun lits(cr: Int): IntArray = cLits[cr]!!

    // =========================================================================
    // Watches. wtab is indexed by literal offset: litoff(l) = 2*|l| + (l<0).
    // Each watcher stores (clauseRef, blockingLit, bin).
    // =========================================================================
    private var wClause = arrayOfNulls<IntArrayList>(0)
    private var wBlit = arrayOfNulls<IntArrayList>(0)
    private var wBin = arrayOfNulls<BooleanList>(0)

    private fun litoff(lit: Int): Int = 2 * vidx(lit) + (if (lit < 0) 1 else 0)

    private fun ensureWatchTables(size: Int) {
        if (wClause.size >= size) return
        val nc = arrayOfNulls<IntArrayList>(size)
        val nb = arrayOfNulls<IntArrayList>(size)
        val nn = arrayOfNulls<BooleanList>(size)
        for (i in wClause.indices) { nc[i] = wClause[i]; nb[i] = wBlit[i]; nn[i] = wBin[i] }
        for (i in wClause.size until size) { nc[i] = IntArrayList(); nb[i] = IntArrayList(); nn[i] = BooleanList() }
        wClause = nc; wBlit = nb; wBin = nn
    }

    private fun wc(lit: Int): IntArrayList = wClause[litoff(lit)]!!
    private fun wbl(lit: Int): IntArrayList = wBlit[litoff(lit)]!!
    private fun wbn(lit: Int): BooleanList = wBin[litoff(lit)]!!

    // =========================================================================
    // Per-variable state (index 1..max_var, 0 unused).
    // =========================================================================
    private var maxVar = 0
    private var vals = signedCharArray(1)        // {-1,0,+1} per variable index
    private var vLevel = IntArray(1)
    private var vTrail = IntArray(1)
    private var vReason = IntArray(1) { CREF_NONE }   // reason clause ref, or CREF_NONE

    // trail / control
    private val trail = IntArrayList()
    // control (decision levels): decision lit, trail start, seen_count, seen_trail per frame
    private val ctlDecision = IntArrayList()
    private val ctlTrail = IntArrayList()
    private val ctlSeenCount = IntArrayList()
    private val ctlSeenTrail = IntArrayList()
    private var level = 0
    private var propagated = 0
    private var numAssigned = 0
    private var conflict = CREF_NONE

    // clauses list (all clauses, irredundant + redundant), as arena refs
    private val clauses = IntArrayList()

    // EVSIDS scores (stable mode)
    private var stab = DoubleArray(1)
    private var scoreInc = 1.0
    // binary max-heap over variables by score
    private val heapArr = IntArrayList()
    private var heapPos = IntArray(1) { HEAP_INVALID }

    // VMTF queue (focused mode)
    private var linkPrev = IntArray(1)
    private var linkNext = IntArray(1)
    private var qFirst = 0
    private var qLast = 0
    private var qUnassigned = 0
    private var qBumped = 0L
    private var btab = LongArray(1)
    private var bumpedCounter = 0L

    // phases
    private var phaseSaved = signedCharArray(1)
    private var phaseTarget = signedCharArray(1)
    private var phaseBest = signedCharArray(1)
    private var targetAssigned = 0
    private var bestAssigned = 0
    private var noConflictUntil = 0

    // analyze scratch
    private var seen = signedCharArray(1)
    private val analyzed = IntArrayList()
    private val levelsSeen = IntArrayList()
    private val clause = IntArrayList()           // learned clause literals
    private var removable = signedCharArray(1)
    private var poison = signedCharArray(1)
    private var keepFlag = signedCharArray(1)
    private val minimized = IntArrayList()

    // averages / restarts / reduce
    private var glueFast = Ema()
    private var glueSlow = Ema()
    private var stable = false
    private var conflicts = 0L
    private var restarts = 0L
    private var reductions = 0L
    private var stabphases = 0L
    private var limRestart = 0L
    private var limReduce = 0L
    private var limStabilize = 0L
    private var lastStabilizeConflicts = 0L
    // reluctant doubling (Luby) for stable restarts
    private var relU = 1L
    private var relV = 1L
    private var relPeriod = 0L
    private var relTrigger = false
    private var incStabilizeSet = false
    private var incStabilize = 0L

    private var unsat = false
    private var iterating = false

    // model (per variable index 1..max_var)
    private var model = signedCharArray(1)

    // averages swap on mode switch
    private var averagesSwapped = false
    private val savedGlueFast = Ema()
    private val savedGlueSlow = Ema()

    init {
        // variable 0 is unused (1-based like CaDiCaL). Root control frame.
        ctlDecision.add(0); ctlTrail.add(0); ctlSeenCount.add(0); ctlSeenTrail.add(0)
        // reserve numVarsHint capacity but create variables lazily.
        val cap = maxOf(numVarsHint + 1, 1)
        ensureVarArrays(cap)
    }

    // =========================================================================
    // literal / watch helpers
    // =========================================================================
    private fun vidx(lit: Int): Int = if (lit < 0) -lit else lit
    private fun valOfLit(lit: Int): Int {
        val v = vals[vidx(lit)].toInt()
        return if (lit < 0) -v else v
    }
    private fun valOfVar(idx: Int): Int = vals[idx].toInt()

    // =========================================================================
    // heap (max-heap by score, heap.hpp)
    // =========================================================================
    private fun heapContains(e: Int): Boolean {
        if (e >= heapPos.size) return false
        return heapPos[e] != HEAP_INVALID
    }
    private fun heapLess(a: Int, b: Int): Boolean {
        // 'less' means a is worse. Max-heap keeps larger score on top; tie-break larger index.
        if (stab[a] < stab[b]) return true
        if (stab[a] > stab[b]) return false
        return a < b
    }
    private fun heapExchange(a: Int, b: Int) {
        val i = heapPos[a]; val j = heapPos[b]
        val ta = heapArr[i]; heapArr[i] = heapArr[j]; heapArr[j] = ta
        heapPos[a] = j; heapPos[b] = i
    }
    private fun heapUp(e: Int) {
        var p: Int
        while (heapPos[e] > 0) {
            p = heapArr[(heapPos[e] - 1) / 2]
            if (!heapLess(p, e)) break
            heapExchange(p, e)
        }
    }
    private fun heapDown(e: Int) {
        while (2 * heapPos[e] + 1 < heapArr.size) {
            var c = heapArr[2 * heapPos[e] + 1]
            if (2 * heapPos[e] + 2 < heapArr.size) {
                val r = heapArr[2 * heapPos[e] + 2]
                if (heapLess(c, r)) c = r
            }
            if (!heapLess(e, c)) break
            heapExchange(e, c)
        }
    }
    private fun heapPush(e: Int) {
        val i = heapArr.size
        heapArr.add(e)
        heapPos[e] = i
        heapUp(e)
        heapDown(e)
    }
    private fun heapFront(): Int = heapArr[0]
    private fun heapPop(): Int {
        val res = heapArr[0]; val last = heapArr[heapArr.size - 1]
        if (heapArr.size > 1) heapExchange(res, last)
        heapPos[res] = HEAP_INVALID
        heapArr.removeLast()
        if (heapArr.size > 1) heapDown(last)
        return res
    }
    private fun heapUpdate(e: Int) { heapUp(e); heapDown(e) }

    // =========================================================================
    // VMTF queue
    // =========================================================================
    private fun updateQueueUnassigned(idx: Int) {
        qUnassigned = idx
        qBumped = btab[idx]
    }
    private fun qDequeue(idx: Int) {
        val prev = linkPrev[idx]; val next = linkNext[idx]
        if (prev != 0) linkNext[prev] = next else qFirst = next
        if (next != 0) linkPrev[next] = prev else qLast = prev
    }
    private fun qEnqueue(idx: Int) {
        linkPrev[idx] = qLast
        if (qLast != 0) linkNext[qLast] = idx else qFirst = idx
        qLast = idx
        linkNext[idx] = 0
    }
    private fun initEnqueue(idx: Int) {
        linkNext[idx] = 0
        if (qLast != 0) linkNext[qLast] = idx else qFirst = idx
        btab[idx] = ++bumpedCounter
        linkPrev[idx] = qLast
        qLast = idx
        updateQueueUnassigned(qLast)
    }

    private fun useScores(): Boolean = OPT_score && stable

    // =========================================================================
    // new variable (lazy)
    // =========================================================================
    private fun ensureVarArrays(cap: Int) {
        if (vals.size >= cap) return
        var n = vals.size
        while (n < cap) n *= 2
        vals = vals.copyOf(n)
        vLevel = vLevel.copyOf(n)
        vTrail = vTrail.copyOf(n)
        vReason = vReason.copyOf(n).also { for (i in vReason.size until n) it[i] = CREF_NONE }
        stab = stab.copyOf(n)
        heapPos = heapPos.copyOf(n).also { for (i in heapPos.size until n) it[i] = HEAP_INVALID }
        linkPrev = linkPrev.copyOf(n)
        linkNext = linkNext.copyOf(n)
        btab = btab.copyOf(n)
        phaseSaved = phaseSaved.copyOf(n)
        phaseTarget = phaseTarget.copyOf(n)
        phaseBest = phaseBest.copyOf(n)
        seen = seen.copyOf(n)
        removable = removable.copyOf(n)
        poison = poison.copyOf(n)
        keepFlag = keepFlag.copyOf(n)
    }

    private fun newVar() {
        val idx = ++maxVar
        ensureVarArrays(idx + 1)
        vals[idx] = 0
        vLevel[idx] = 0
        vTrail[idx] = 0
        vReason[idx] = CREF_NONE
        ensureWatchTables(2 * (maxVar + 1))
        stab[idx] = 0.0
        heapPos[idx] = HEAP_INVALID
        btab[idx] = 0
        phaseSaved[idx] = 0
        phaseTarget[idx] = 0
        phaseBest[idx] = 0
        seen[idx] = 0
        removable[idx] = 0
        poison[idx] = 0
        keepFlag[idx] = 0
        // register in both structures (queue always, heap always) so both modes work
        initEnqueue(idx)
        heapPush(idx)
        numVars = maxVar
    }
    private fun ensureVar(idx: Int) {
        while (maxVar < idx) newVar()
    }

    // =========================================================================
    // scores (EVSIDS)
    // =========================================================================
    private fun evsidsLimitHit(s: Double): Boolean = s > 1e150
    private fun rescaleVariableScores() {
        var divider = scoreInc
        for (idx in 1..maxVar) {
            val tmp = stab[idx]
            if (tmp > divider) divider = tmp
        }
        val factor = 1.0 / divider
        for (idx in 1..maxVar) stab[idx] = scoreRound(stab[idx] * factor)
        scoreInc *= factor
    }
    private fun bumpVariableScore(lit: Int) {
        val idx = vidx(lit)
        var oldScore = stab[idx]
        var newScore = scoreRound(oldScore + scoreInc)
        if (evsidsLimitHit(newScore)) {
            rescaleVariableScores()
            oldScore = stab[idx]
            newScore = scoreRound(oldScore + scoreInc)
        }
        stab[idx] = newScore
        if (heapContains(idx)) heapUpdate(idx)
    }
    private fun bumpVariableScoreInc() {
        val f = 1e3 / OPT_scorefactor.toDouble()
        var newScoreInc = scoreInc * f
        if (evsidsLimitHit(newScoreInc)) {
            rescaleVariableScores()
            newScoreInc = scoreInc * f
        }
        scoreInc = newScoreInc
    }

    // VMTF bump
    private fun bumpQueue(lit: Int) {
        val idx = vidx(lit)
        if (linkNext[idx] == 0) return
        qDequeue(idx)
        qEnqueue(idx)
        btab[idx] = ++bumpedCounter
        if (vals[idx].toInt() == 0) updateQueueUnassigned(idx)
    }
    private fun bumpVariable(lit: Int) {
        if (useScores()) bumpVariableScore(lit) else bumpQueue(lit)
    }
    private fun bumpedOf(lit: Int): Long = btab[vidx(lit)]

    private fun bumpVariables() {
        if (!useScores()) {
            // stable sort analyzed by bumped ascending -- CaDiCaL bumps in queue order.
            stableSortByBumped(analyzed)
        }
        for (t in 0 until analyzed.size) bumpVariable(analyzed[t])
        if (useScores()) bumpVariableScoreInc()
    }

    /** Stable insertion sort of [list] by bumped stamp ascending (matches std::stable_sort). */
    private fun stableSortByBumped(list: IntArrayList) {
        val a = list.toArray()
        for (i in 1 until a.size) {
            val key = a[i]; val kb = bumpedOf(key)
            var j = i - 1
            while (j >= 0 && bumpedOf(a[j]) > kb) { a[j + 1] = a[j]; j-- }
            a[j + 1] = key
        }
        list.clear()
        for (x in a) list.add(x)
    }

    // =========================================================================
    // assign
    // =========================================================================
    private fun assign(lit: Int, reasonIn: Int, rc: Char) {
        val idx = vidx(lit)
        var reason = reasonIn
        val litLevel: Int
        if (reason == CREF_NONE) {
            litLevel = 0 // unit
        } else if (reason == CREF_DECISION) {
            litLevel = level
            reason = CREF_NONE
        } else {
            litLevel = level // no chrono: assignment level == current level
        }
        val actualReason = if (litLevel == 0) CREF_NONE else reason
        vLevel[idx] = litLevel
        vTrail[idx] = trail.size
        vReason[idx] = actualReason
        numAssigned++
        val tmp: Byte = if (lit < 0) (-1).toByte() else 1.toByte()
        vals[idx] = tmp
        phaseSaved[idx] = tmp
        trail.add(lit)
        tr("ASSIGN $lit reason=$rc")
    }

    private fun newTrailLevel(lit: Int) {
        level++
        ctlDecision.add(lit); ctlTrail.add(trail.size); ctlSeenCount.add(0); ctlSeenTrail.add(trail.size)
    }

    // =========================================================================
    // watch
    // =========================================================================
    private fun watchLiteral(lit: Int, blit: Int, cr: Int) {
        wc(lit).add(cr); wbl(lit).add(blit); wbn(lit).add(cSize[cr] == 2)
    }
    private fun watchClause(cr: Int) {
        val l0 = lits(cr)[0]; val l1 = lits(cr)[1]
        watchLiteral(l0, l1, cr)
        watchLiteral(l1, l0, cr)
    }
    private fun removeWatch(lit: Int, cr: Int) {
        val a = wc(lit); val b = wbl(lit); val c = wbn(lit)
        var i = 0
        while (i < a.size) {
            if (a[i] == cr) { a.removeAt(i); b.removeAt(i); c.removeAt(i); return }
            i++
        }
    }
    private fun unwatchClause(cr: Int) {
        removeWatch(lits(cr)[0], cr)
        removeWatch(lits(cr)[1], cr)
    }

    // =========================================================================
    // new clause
    // =========================================================================
    private fun newClause(litsIn: IntArray, redundant: Boolean, glue: Int): Int {
        val cr = newClauseArena(litsIn, redundant, glue)
        clauses.add(cr)
        if (cSize[cr] >= 2) watchClause(cr)
        return cr
    }

    // =========================================================================
    // propagate (propagate.cpp)
    // =========================================================================
    private fun propagate(): Boolean {
        while (conflict == CREF_NONE && propagated != trail.size) {
            val lit = -trail[propagated++]
            val ws = wc(lit); val bs = wbl(lit); val ns = wbn(lit)
            var i = 0; var j = 0
            while (i != ws.size) {
                // Watch w = ws[j++] = ws[i++]
                val wcr = ws[i]; val wblit = bs[i]; val wbinv = ns[i]
                ws[j] = wcr; bs[j] = wblit; ns[j] = wbinv; j++; i++
                val b = valOfLit(wblit)
                if (b > 0) continue // blocking literal satisfied
                if (wbinv) {
                    if (b < 0) conflict = wcr
                    else assign(wblit, wcr, 'C')
                } else {
                    if (conflict != CREF_NONE) break
                    if (cGarbage[wcr]) { j--; continue }
                    val ls = lits(wcr)
                    val other = ls[0] xor ls[1] xor lit
                    val u = valOfLit(other)
                    if (u > 0) {
                        bs[j - 1] = other
                    } else {
                        val size = cSize[wcr]
                        val mid = cPos[wcr]
                        var k = mid
                        var r = 0
                        var v = -1
                        while (k != size) {
                            r = ls[k]; v = valOfLit(r)
                            if (v >= 0) break
                            k++
                        }
                        if (v < 0) {
                            k = 2
                            while (k != mid) {
                                r = ls[k]; v = valOfLit(r)
                                if (v >= 0) break
                                k++
                            }
                        }
                        cPos[wcr] = k
                        if (v > 0) {
                            bs[j - 1] = r
                        } else if (v == 0) {
                            // found new unassigned replacement literal to watch
                            ls[0] = other
                            ls[1] = r
                            ls[k] = lit
                            watchLiteral(r, lit, wcr)
                            j--
                        } else if (u == 0) {
                            // other watch unassigned, rest false -> unit
                            assign(other, wcr, 'C')
                        } else {
                            conflict = wcr
                            break
                        }
                    }
                }
            }
            if (j != i) {
                while (i != ws.size) { ws[j] = ws[i]; bs[j] = bs[i]; ns[j] = ns[i]; j++; i++ }
                ws.shrinkTo(j); bs.shrinkTo(j); ns.shrinkTo(j)
            }
        }
        if (conflict == CREF_NONE) {
            noConflictUntil = propagated
        } else {
            conflicts++
            noConflictUntil = ctlTrail[level]
            tr("CONFLICT")
        }
        return conflict == CREF_NONE
    }

    // =========================================================================
    // analyze support
    // =========================================================================
    private var glueStampCounter = 0L
    private var gtab = LongArray(1)
    private fun recomputeGlue(cr: Int): Int {
        val stamp = ++glueStampCounter
        if (gtab.size < level + 1) gtab = gtab.copyOf(level + 1)
        var res = 0
        for (lit in lits(cr)) {
            val lv = vLevel[vidx(lit)]
            if (lv >= gtab.size) gtab = gtab.copyOf(lv + 1)
            if (gtab[lv] == stamp) continue
            gtab[lv] = stamp
            res++
        }
        return res
    }
    private fun promoteClause(cr: Int, newGlue: Int) {
        if (newGlue < cGlue[cr]) cGlue[cr] = newGlue
    }
    private fun maxUsedVal(): Int = 2
    private fun bumpClause(cr: Int) {
        cUsed[cr] = maxUsedVal()
        if (!cRedundant[cr]) return
        val newGlue = recomputeGlue(cr)
        if (newGlue < cGlue[cr]) promoteClause(cr, newGlue)
    }

    private fun analyzeLiteral(lit: Int, openBox: IntArray) {
        val idx = vidx(lit)
        val lv = vLevel[idx]
        if (lv == 0) return
        if (seen[idx].toInt() != 0) return
        seen[idx] = 1
        analyzed.add(lit)
        if (lv < level) clause.add(lit)
        // control frame lv: seen_count / seen_trail
        val sc = ctlSeenCount[lv]
        if (sc == 0) {
            levelsSeen.add(lv)
            ctlSeenTrail[lv] = vTrail[idx]
        }
        ctlSeenCount[lv] = sc + 1
        if (vTrail[idx] < ctlSeenTrail[lv]) ctlSeenTrail[lv] = vTrail[idx]
        if (lv == level) openBox[0]++
    }
    private fun analyzeReason(lit: Int, reasonCr: Int, openBox: IntArray) {
        bumpClause(reasonCr)
        for (other in lits(reasonCr)) if (other != lit) analyzeLiteral(other, openBox)
    }

    private fun clearAnalyzedLiterals() {
        for (t in 0 until analyzed.size) seen[vidx(analyzed[t])] = 0
        analyzed.clear()
    }
    private fun clearAnalyzedLevels() {
        for (t in 0 until levelsSeen.size) {
            val l = levelsSeen[t]
            if (l < ctlSeenCount.size) {
                ctlSeenCount[l] = 0
                ctlSeenTrail[l] = ctlTrail[l]
            }
        }
        levelsSeen.clear()
    }

    // =========================================================================
    // recursive minimization (minimize.cpp)
    // =========================================================================
    private fun minimizeLiteral(lit: Int, depth: Int): Boolean {
        val idx = vidx(lit)
        val lv = vLevel[idx]
        if (lv == 0 || removable[idx].toInt() != 0 || keepFlag[idx].toInt() != 0) return true
        if (vReason[idx] == CREF_NONE || poison[idx].toInt() != 0 || lv == level) return false
        val seenCount = ctlSeenCount[lv]
        if (depth == 0 && seenCount < 2) return false
        if (vTrail[idx] <= ctlSeenTrail[lv]) return false
        if (depth > OPT_minimizedepth) return false
        var res = true
        for (other in lits(vReason[idx])) {
            if (other == lit) continue
            res = minimizeLiteral(-other, depth + 1)
            if (!res) break
        }
        if (res) removable[idx] = 1 else poison[idx] = 1
        minimized.add(idx)
        return res
    }

    private fun minimizeClause() {
        // sort clause by trail order (ascending trail)
        sortClauseByTrailAsc()
        var j = 0
        for (i in 0 until clause.size) {
            val lit = clause[i]
            if (minimizeLiteral(-lit, 0)) {
                // removed
            } else {
                keepFlag[vidx(lit)] = 1
                clause[j] = lit; j++
            }
        }
        clause.shrinkTo(j)
        for (t in 0 until minimized.size) {
            val idx = minimized[t]
            removable[idx] = 0
            poison[idx] = 0
        }
        for (t in 0 until clause.size) keepFlag[vidx(clause[t])] = 0
        minimized.clear()
    }

    private fun sortClauseByTrailAsc() {
        // std::sort with comparator var(a).trail < var(b).trail. std::sort is not stable, but
        // the trail index is a strict total order over distinct variables, so any correct sort
        // gives the identical result. Use a plain insertion sort for determinism.
        val a = clause.toArray()
        for (i in 1 until a.size) {
            val key = a[i]; val kt = vTrail[vidx(key)]
            var j = i - 1
            while (j >= 0 && vTrail[vidx(a[j])] > kt) { a[j + 1] = a[j]; j-- }
            a[j + 1] = key
        }
        clause.clear()
        for (x in a) clause.add(x)
    }

    // =========================================================================
    // new driving clause
    // =========================================================================
    private var jump = 0
    private fun newDrivingClause(glue: Int): Int {
        val size = clause.size
        val res: Int
        if (size == 0) { jump = 0; res = CREF_NONE }
        else if (size == 1) { jump = 0; res = CREF_NONE }
        else {
            // sort clause by decreasing (level,trail) so highest-level lits go first.
            // The (level,trail) pair is a strict total order over distinct vars -> any sort
            // yields the same order; insertion sort for determinism.
            sortClauseByLevelTrailDesc()
            jump = vLevel[vidx(clause[1])]
            res = newClause(clause.toArray(), true, glue)
        }
        return res
    }
    private fun sortClauseByLevelTrailDesc() {
        val a = clause.toArray()
        for (i in 1 until a.size) {
            val key = a[i]
            val kl = vLevel[vidx(key)]; val kt = vTrail[vidx(key)]
            var j = i - 1
            while (j >= 0) {
                val al = vLevel[vidx(a[j])]; val at = vTrail[vidx(a[j])]
                // a[j] should come after key iff key is "greater": (kl>al) || (kl==al && kt>at)
                val keyGreater = if (kl != al) kl > al else kt > at
                if (!keyGreater) break
                a[j + 1] = a[j]; j--
            }
            a[j + 1] = key
        }
        clause.clear()
        for (x in a) clause.add(x)
    }

    // =========================================================================
    // backtrack
    // =========================================================================
    private fun copyPhases(dst: ByteArray) {
        for (i in 1..maxVar) {
            val tmp = phaseSaved[i]
            if (tmp.toInt() != 0) dst[i] = tmp
        }
    }
    private fun updateTargetAndBest() {
        if (!stable) return
        if (noConflictUntil > targetAssigned) {
            copyPhases(phaseTarget)
            targetAssigned = noConflictUntil
        }
        if (noConflictUntil > bestAssigned) {
            copyPhases(phaseBest)
            bestAssigned = noConflictUntil
        }
    }
    private fun unassign(lit: Int) {
        val idx = vidx(lit)
        vals[idx] = 0
        numAssigned--
        if (!heapContains(idx)) heapPush(idx)
        if (qBumped < btab[idx]) updateQueueUnassigned(idx)
    }
    private fun backtrack(newLevel: Int) {
        if (newLevel == level) return
        updateTargetAndBest()
        val assigned = ctlTrail[newLevel + 1]
        val end = trail.size
        for (i in assigned until end) unassign(trail[i])
        trail.shrinkTo(assigned)
        if (propagated > assigned) propagated = assigned
        if (noConflictUntil > assigned) noConflictUntil = assigned
        // control.resize(new_level + 1)
        val newSize = newLevel + 1
        ctlDecision.shrinkTo(newSize); ctlTrail.shrinkTo(newSize)
        ctlSeenCount.shrinkTo(newSize); ctlSeenTrail.shrinkTo(newSize)
        level = newLevel
    }

    // =========================================================================
    // analyze (analyze.cpp, no-chrono/no-otfs path)
    // =========================================================================
    private val analyzeOpen = IntArray(1)
    private fun analyze() {
        if (level == 0) {
            unsat = true
            conflict = CREF_NONE
            return
        }

        var reason = conflict
        var i = trail.size
        analyzeOpen[0] = 0
        var uip = 0

        while (true) {
            analyzeReason(uip, reason, analyzeOpen)
            uip = 0
            while (uip == 0) {
                val lit = trail[--i]
                if (seen[vidx(lit)].toInt() == 0) continue
                if (vLevel[vidx(lit)] == level) uip = lit
            }
            analyzeOpen[0]--
            if (analyzeOpen[0] == 0) break
            reason = vReason[vidx(uip)]
        }
        clause.add(-uip)

        var size = clause.size
        val glue = levelsSeen.size - 1
        glueFast.update(glue.toDouble())
        glueSlow.update(glue.toDouble())

        if (size > 1) {
            if (OPT_minimize) minimizeClause()
            size = clause.size
            if (OPT_bump) bumpVariables()
        }

        val driving = newDrivingClause(glue)
        backtrack(jump)

        if (uip != 0) {
            assign(-uip, driving, 'C')
        } else {
            unsat = true
        }

        if (stable) reluctantTick()

        clearAnalyzedLiterals()
        clearAnalyzedLevels()
        clause.clear()
        conflict = CREF_NONE

        if (size == 1) iterating = true
    }

    // =========================================================================
    // reluctant (Luby) doubling
    // =========================================================================
    private fun reluctantEnable() {
        relU = 1; relV = 1
        relPeriod = OPT_reluctantint
        relTrigger = false
    }
    private fun reluctantDisable() {
        relU = 0; relV = 0; relPeriod = 0
        relTrigger = false
    }
    private fun reluctantTick() {
        if (relPeriod == 0L) return
        relPeriod--
        if (relPeriod != 0L) return
        if ((relU and -relU) == relV) {
            relU++
            relV = 1
        } else {
            relV *= 2
        }
        var p = relV
        if (OPT_reluctantmax != 0L && p > OPT_reluctantmax / OPT_reluctantint) {
            relU = 1; relV = 1
            p = 1
        }
        relPeriod = p * OPT_reluctantint
        relTrigger = true
    }

    // =========================================================================
    // stabilizing (restart.cpp)
    // =========================================================================
    private fun stabilizing(): Boolean {
        if (!OPT_stabilize) return false
        if (conflicts <= limStabilize) return stable
        val deltaConflicts = conflicts - lastStabilizeConflicts
        if (!incStabilizeSet) {
            incStabilize = deltaConflicts
            if (incStabilize < 1) incStabilize = 1
            incStabilizeSet = true
        }
        var nextDelta = incStabilize
        val sp = stabphases + 1
        nextDelta *= sp * sp
        limStabilize = conflicts + nextDelta
        if (limStabilize <= conflicts) limStabilize = conflicts + 1
        lastStabilizeConflicts = conflicts
        stable = !stable
        if (stable) stabphases++
        swapAverages()
        if (stable) reluctantEnable() else reluctantDisable()
        return stable
    }

    // =========================================================================
    // restart (restart.cpp)
    // =========================================================================
    private fun restarting(): Boolean {
        if (!OPT_restart) return false
        if (level < 2) return false
        if (stabilizing() && OPT_reluctant) return relTrigger
        if (conflicts <= limRestart) return false
        val f = glueFast.value
        val p = if (stable) OPT_restartmarginstable else OPT_restartmarginfocused
        val m = (100.0 + p) / 100.0
        val s = glueSlow.value
        val l = m * s
        return l <= f
    }
    private fun restart() {
        restarts++
        relTrigger = false
        backtrack(0)
        limRestart = conflicts + OPT_restartint
        tr("RESTART")
    }

    // =========================================================================
    // averages swap on mode switch
    // =========================================================================
    private fun initAverages() {
        glueFast = initEma(OPT_emagluefast)
        glueSlow = initEma(OPT_emaglueslow)
    }
    private fun swapAverages() {
        val tf = Ema(); tf.copyFrom(glueFast); glueFast.copyFrom(savedGlueFast); savedGlueFast.copyFrom(tf)
        val ts = Ema(); ts.copyFrom(glueSlow); glueSlow.copyFrom(savedGlueSlow); savedGlueSlow.copyFrom(ts)
        if (!averagesSwapped) initAverages()
        averagesSwapped = true
    }

    // =========================================================================
    // reduce (reduce.cpp)
    // =========================================================================
    private fun reducing(): Boolean {
        if (!OPT_reduce) return false
        var any = false
        for (t in 0 until clauses.size) {
            val c = clauses[t]
            if (cRedundant[c] && !cGarbage[c]) { any = true; break }
        }
        if (!any) return false
        return conflicts >= limReduce
    }
    private fun protectReasons() {
        for (t in 0 until trail.size) {
            val r = vReason[vidx(trail[t])]
            if (r != CREF_NONE && r != CREF_DECISION) cReason[r] = true
        }
    }
    private fun unprotectReasons() {
        for (t in 0 until clauses.size) cReason[clauses[t]] = false
    }
    private fun reduce() {
        reductions++
        protectReasons()

        val tier1 = OPT_reducetier1glue
        val tier2 = maxOf(tier1, OPT_reducetier2glue)
        val stack = IntArrayList()
        for (t in 0 until clauses.size) {
            val c = clauses[t]
            if (!cRedundant[c]) continue
            if (cGarbage[c]) continue
            if (cReason[c]) continue
            val used = cUsed[c]
            if (used != 0) cUsed[c] = used - 1
            if (cGlue[c] <= tier1 && used != 0) continue
            if (cGlue[c] <= tier2 && used >= maxUsedVal() - 1) continue
            stack.add(c)
        }
        stableSortReduce(stack)
        var target = (1e-2 * OPT_reducetarget * stack.size).toInt()
        if (target > stack.size) target = stack.size
        for (t in 0 until target) cGarbage[stack[t]] = true

        garbageCollect()
        unprotectReasons()

        var delta = OPT_reduceint
        var factor = kotlin.math.sqrt(conflicts.toDouble())
        if (factor < 1) factor = 1.0
        delta = (delta * factor).toLong()
        if (delta < 1) delta = 1
        limReduce = conflicts + delta
        tr("REDUCE")
    }

    /**
     * Stable sort of reduce candidates by (glue desc, size desc). The C reference uses
     * std::stable_sort with this one-directional predicate; we mirror it with a stable
     * insertion sort so incomparable clauses keep input order identically (same lesson as
     * MiniSat's reduceDB non-total-order sort).
     */
    private fun stableSortReduce(stack: IntArrayList) {
        val a = stack.toArray()
        // predicate: c precedes d iff (c.glue>d.glue) || (c.glue==d.glue && c.size>d.size)
        for (i in 1 until a.size) {
            val key = a[i]
            var j = i - 1
            while (j >= 0 && reduceLess(key, a[j])) { a[j + 1] = a[j]; j-- }
            a[j + 1] = key
        }
        stack.clear()
        for (x in a) stack.add(x)
    }
    private fun reduceLess(c: Int, d: Int): Boolean {
        if (cGlue[c] > cGlue[d]) return true
        if (cGlue[c] < cGlue[d]) return false
        return cSize[c] > cSize[d]
    }

    private fun garbageCollect() {
        // unwatch garbage clauses, drop them from the clause list, keep the rest.
        // First unwatch (needs live lits[0]/lits[1]).
        for (t in 0 until clauses.size) {
            val c = clauses[t]
            if (cGarbage[c] && cSize[c] >= 2) unwatchClause(c)
        }
        val kept = IntArrayList()
        for (t in 0 until clauses.size) {
            val c = clauses[t]
            if (!cGarbage[c]) kept.add(c)
        }
        clauses.clear()
        for (t in 0 until kept.size) clauses.add(kept[t])
    }

    // =========================================================================
    // decide (decide.cpp / phases.cpp)
    // =========================================================================
    private fun nextDecisionVariableOnQueue(): Int {
        var res = qUnassigned
        while (vals[res].toInt() != 0) res = linkPrev[res]
        if (res != qUnassigned) updateQueueUnassigned(res)
        return res
    }
    private fun nextDecisionVariableWithBestScore(): Int {
        var res: Int
        while (true) {
            res = heapFront()
            if (vals[res].toInt() == 0) break
            heapPop()
        }
        return res
    }
    private fun nextDecisionVariable(): Int =
        if (useScores()) nextDecisionVariableWithBestScore() else nextDecisionVariableOnQueue()

    private fun decidePhase(idx: Int, target: Boolean): Int {
        val initialPhase = if (OPT_phase) 1 else -1
        var phase = 0
        if (phase == 0 && target) phase = phaseTarget[idx].toInt()
        if (phase == 0) phase = phaseSaved[idx].toInt()
        if (phase == 0) phase = initialPhase
        return phase * idx
    }
    // ---- assumptions (decision literals forced before free search) ----
    // CaDiCaL consumes assumptions in decide(): while level < assumptions.size the next
    // decision is assumptions[level] instead of a heuristic pick, so satisfied() must also
    // wait for all assumptions to be on the trail. A falsified assumption ends the solve
    // as UNSAT (decide returns 20, like the C loop). Only the verdict + model are needed
    // here, so CaDiCaL's frozen-literal / failed-core bookkeeping is not ported. After the
    // solve the trail is rolled back, so the same instance can be solved again under other
    // assumptions on the same clause set. Signed DIMACS literals.
    private var assumptions: IntArray = IntArray(0)

    private fun satisfied(): Boolean {
        // not satisfied until every assumption has been forced onto its own level
        if (level < assumptions.size) return false
        return numAssigned == maxVar
    }

    // returns 0 to continue, 20 if an assumption is falsified (-> UNSAT for this solve)
    private fun decide(): Int {
        if (level < assumptions.size) {
            val lit = assumptions[level]
            ensureVar(vidx(lit))
            val v = valOfLit(lit)
            if (v < 0) {
                // assumption falsified under the current (assumption-forced) prefix
                return 20
            } else if (v > 0) {
                // already satisfied: add a pseudo decision level, like CaDiCaL. This is not
                // a real branch, so no DECIDE trace is emitted (matches new_trail_level(0)).
                newTrailLevel(0)
                return 0
            } else {
                newTrailLevel(lit)
                tr("DECIDE $lit")
                assign(lit, CREF_DECISION, 'D')
                return 0
            }
        }
        val idx = nextDecisionVariable()
        val target = (OPT_target > 1 || (stable && OPT_target != 0))
        val decision = decidePhase(idx, target)
        newTrailLevel(decision)
        tr("DECIDE $decision")
        assign(decision, CREF_DECISION, 'D')
        return 0
    }

    // =========================================================================
    // add clause (root-level simplification)
    // =========================================================================
    private val addBuf = IntArrayList()
    private fun addClauseInternal(raw: IntArray): Boolean {
        addBuf.clear()
        var taut = false
        for (lit in raw) {
            ensureVar(vidx(lit))
            val v = valOfLit(lit)
            if (v > 0 && vLevel[vidx(lit)] == 0) { taut = true; break }
            if (v < 0 && vLevel[vidx(lit)] == 0) continue // drop root-false
            var dup = false
            var i = 0
            while (i < addBuf.size) {
                val q = addBuf[i]
                if (q == lit) { dup = true; break }
                if (q == -lit) { taut = true; break }
                i++
            }
            if (taut) break
            if (!dup) addBuf.add(lit)
        }
        if (taut) return true
        if (addBuf.size == 0) { unsat = true; return false }
        if (addBuf.size == 1) {
            assign(addBuf[0], CREF_NONE, 'U')
            return propagate()
        }
        newClause(addBuf.toArray(), false, 0)
        return true
    }

    // =========================================================================
    // solve loop (internal.cpp cdcl_loop, inprocessing disabled)
    // =========================================================================
    private fun solveLoop(): Int {
        if (unsat) { tr("RESULT UNSAT"); return 20 }
        initAverages()
        averagesSwapped = false
        if (!propagate()) {
            analyze()
            if (unsat) { tr("RESULT UNSAT"); return 20 }
        }
        limStabilize = OPT_stabilizeinit

        var res = 0
        while (res == 0) {
            if (unsat) res = 20
            else if (!propagate()) analyze()
            else if (iterating) iterating = false
            else if (satisfied()) res = 10
            else if (restarting()) restart()
            else if (reducing()) reduce()
            else res = decide() // 0 continue, 20 if an assumption is falsified
        }

        if (res == 10) {
            captureModel()
            tr("RESULT SAT")
        } else {
            tr("RESULT UNSAT")
        }
        return res
    }

    private fun captureModel() {
        model = signedCharArray(maxVar + 1)
        for (i in 1..maxVar) model[i] = vals[i]
    }

    // =========================================================================
    // Public SatSolver API
    // =========================================================================
    override fun addClause(literals: IntArray) {
        if (unsat) return
        addClauseInternal(literals)
    }

    override fun solve(): SatResult {
        val r = solveLoop()
        return if (r == 10) SatResult.SAT else SatResult.UNSAT
    }

    /**
     * Solve UNDER the given [assumptions] (signed DIMACS literals); see [SatSolver.solve].
     * The assumptions are consumed as forced decisions in [decide]; after the solve the trail
     * is rolled back to level 0 so the same instance can be solved again under different
     * assumptions on the same clause set. On SAT, [valueOf] is the model of this solve; on
     * UNSAT the assumptions were jointly inconsistent with the formula. Only the verdict and
     * the model are produced — no failed-assumption core.
     */
    override fun solve(assumptions: IntArray): SatResult {
        this.assumptions = assumptions
        val r = try {
            solveLoop()
        } finally {
            // roll back the assumption-forced prefix so the instance is reusable
            if (level > 0) backtrack(0)
            this.assumptions = IntArray(0)
        }
        return if (r == 10) SatResult.SAT else SatResult.UNSAT
    }

    override fun valueOf(v: Int): Boolean {
        // v is 1..numVars DIMACS. A variable that appeared in no clause was never created
        // (lazy, like the C reference); report false for it (a don't-care).
        if (v < 1 || v >= model.size) return false
        return model[v].toInt() > 0
    }
}

private const val HEAP_INVALID = -1

private fun signedCharArray(n: Int): ByteArray = ByteArray(n)

/** Minimal growable int list (avoids boxing; mirrors the flat-array style of the C port). */
class IntArrayList {
    private var a = IntArray(16)
    var size = 0
        private set
    fun add(x: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun removeLast() { size-- }
    fun removeAt(i: Int) { for (k in i until size - 1) a[k] = a[k + 1]; size-- }
    fun clear() { size = 0 }
    fun shrinkTo(n: Int) { size = n }
    fun toArray(): IntArray = a.copyOf(size)
}

/** Minimal growable boolean list, parallel to [IntArrayList] for the watcher 'bin' flag. */
class BooleanList {
    private var a = BooleanArray(16)
    var size = 0
        private set
    fun add(x: Boolean) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    operator fun get(i: Int): Boolean = a[i]
    operator fun set(i: Int, v: Boolean) { a[i] = v }
    fun removeAt(i: Int) { for (k in i until size - 1) a[k] = a[k + 1]; size-- }
    fun clear() { size = 0 }
    fun shrinkTo(n: Int) { size = n }
}
