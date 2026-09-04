package org.bytefred.ksat.kissat

import org.bytefred.ksat.SatResult
import org.bytefred.ksat.SatSolver
import org.bytefred.ksat.Traceable

/**
 * Faithful Kotlin port of the CORE CDCL solver of kissat (MIT, (c) 2019-2024 Armin Biere
 * and the kissat authors), with ALL inprocessing disabled -> plain, deterministic,
 * trace-matchable CDCL. See ../shadow/kissat-c/kissat_trace.cc for the self-contained
 * instrumented C reference this is transcribed from 1:1.
 *
 * kissat differs from CaDiCaL in several load-bearing ways that this port preserves:
 *   - UNSIGNED internal literals: lit = 2*idx + sign, NOT(lit) = lit xor 1, IDX = lit ushr 1.
 *     values[] is indexed by literal, values[lit] = -values[NOT lit].
 *   - a 2-word large-clause watch encoding (blocking-word then reference-word) in a flat
 *     Int watch list; binary clauses are watch-only (no arena clause).
 *   - conflict analysis via kissat's conflict-level reuse shortcut then a first-UIP
 *     deduction using per-decision-level frame 'used' counters, a level-sorted rebuild,
 *     and recursive minimization.
 *   - a binary max-heap keyed purely by double score with POSITIONAL tie-break (insertion
 *     order), NOT index tie-break (this is the key heap difference from CaDiCaL).
 *   - ADAM-style bias-corrected EMAs of the glue and a reluctant (Luby) restart schedule.
 *   - trail reuse on restart; LBD-tiered reduce with a packed (~size | ~glue<<32) rank.
 *
 * DISABLED (documented core configuration, so C and Kotlin match): chronological
 * backtracking, on-the-fly self-subsumption/strengthening, clause shrinking beyond
 * recursive minimize, reason-side bumping, eager subsumption, random decisions,
 * jump-reasons, rephasing/reordering/warming/lucky, and all inprocessing (+ kitten).
 *
 * FLOATING POINT: kissat stores its heap scores (`heap.score`) and the score increment
 * (`scinc`) as C `double`, and the glue EMAs as `double`. Kotlin/JVM `Double` is IEEE-754
 * identical to C `double`, and every score/EMA update here is transcribed in the same
 * evaluation order, so L1 (byte-for-byte trace) is expected to hold with
 * [ActivityPrecision.FLOAT64] (the default). [ActivityPrecision.FLOAT32] rounds every score
 * write via toFloat().toDouble() for a hypothetical float build / standalone experiments.
 * Not idiomatic Kotlin on purpose -- correspondence to the C beats idiom.
 */
enum class ActivityPrecision { FLOAT32, FLOAT64 }

class Kissat(
    numVarsHint: Int,
    private val activityPrecision: ActivityPrecision = ActivityPrecision.FLOAT64,
    // TEST-ONLY knobs for standalone experiments and RESTART/REDUCE trace coverage: lower
    // them to trigger REDUCE / stabilization on small CNFs. Defaults == upstream kissat.
    // The C reference (kissat_trace.cc) mirrors these via LSREDUCEINIT / LSSTABLEINIT env
    // vars, so a shadowed run must use the same values on both sides.
    reduceIntInit: Long = 300L,
    stableInit: Long = 1000L,
) : SatSolver, Traceable {

    // ---- constants (clause.h / assign.h / literal.h / bump.h / statistics.h) ----
    private companion object {
        const val INVALID_LIT = -1               // 0xffffffff as Int
        const val INVALID_LEVEL = -1
        const val INVALID_REF = -1
        const val DECISION_REASON = -1           // 0xffffffff
        const val UNIT_REASON = -2               // 0xfffffffe
        const val DISCONNECT = -1
        const val DISCONTAIN = -1
        const val MAX_GLUE = (1 shl 19) - 1
        const val MAX_USED = (1 shl 5) - 1       // 31
        const val MAX_GLUE_USED = 127
        const val MAX_SCORE = 1e150

        // options (core configuration; upstream defaults)
        const val OPT_decay = 50                 // per mille
        const val OPT_stable = 1                 // ==2 would start stable; default 1 -> focused
        const val OPT_target = 1
        const val OPT_phase = 1
        const val OPT_phasesaving = 1
        const val OPT_restart = 1
        const val OPT_restartint = 1
        const val OPT_restartmargin = 10         // percent
        const val OPT_reluctantint = 1024L
        const val OPT_reluctantlim = 1048576L
        const val OPT_reduce = 1
        const val OPT_reducelow = 500            // per mille -> *0.1
        const val OPT_reducehigh = 900           // per mille -> *0.1
        const val OPT_tier1 = 2
        const val OPT_tier2 = 6
        const val OPT_minimize = 1
        const val OPT_minimizedepth = 1000
        const val OPT_bump = 1
        const val OPT_emafast = 33.0
        const val OPT_emaslow = 1e5
    }

    private val gReduceInt: Long = reduceIntInit
    private val gStableInit: Long = stableInit

    // literal encoding (literal.h). lit is a non-negative Int (bit0 = sign).
    private fun lit(idx: Int): Int = idx shl 1
    private fun idxOf(lit: Int): Int = lit ushr 1
    private fun not(lit: Int): Int = lit xor 1
    private fun negated(lit: Int): Boolean = (lit and 1) != 0
    private fun initialPhase(): Int = if (OPT_phase != 0) 1 else -1

    private fun scoreRound(x: Double): Double =
        if (activityPrecision == ActivityPrecision.FLOAT32) x.toFloat().toDouble() else x

    override var numVars: Int = 0
        private set

    // ---- trace sink ----
    private var trace: ((String) -> Unit)? = null
    override fun setTraceSink(sink: ((String) -> Unit)?) { trace = sink }
    private inline fun tr(line: String) { trace?.invoke(line) }

    private fun dimacs(lit: Int): Int {
        val v = idxOf(lit) + 1
        return if (negated(lit)) -v else v
    }

    // =========================================================================
    // ADAM-style bias-corrected EMA (smooth.c).
    // =========================================================================
    private class Smooth {
        var value = 0.0
        var biased = 0.0
        var alpha = 0.0
        var beta = 0.0
        var exp = 0.0
        fun init(window: Double) {
            alpha = 1.0 / window
            value = 0.0; biased = 0.0
            beta = 1.0 - alpha
            exp = 1.0
        }
        fun update(y: Double) {
            val oldBiased = biased
            val a = alpha
            val b = beta
            val delta = y - oldBiased
            val scaledDelta = a * delta
            val newBiased = oldBiased + scaledDelta
            biased = newBiased
            val oldExp = exp
            val newValue: Double
            if (oldExp != 0.0) {
                var newExp = oldExp * b
                if (newExp == oldExp) {
                    newExp = 0.0
                    newValue = newBiased
                } else {
                    val div = 1 - newExp
                    newValue = newBiased / div
                }
                exp = newExp
            } else {
                newValue = newBiased
            }
            value = newValue
        }
    }

    // =========================================================================
    // Reluctant (Luby) doubling (reluctant.c).
    // =========================================================================
    private class Reluctant {
        var limited = false
        var trigger = false
        var period = 0L
        var wait = 0L
        var u = 0L
        var v = 0L
        var limit = 0L
        fun enable(p0: Long, l: Long) {
            var p = p0
            if (l != 0L && p > l) p = l
            limited = l > 0
            trigger = false
            period = p; wait = p
            u = 1; v = 1
            limit = l
        }
        fun disable() {
            limited = false; trigger = false
            period = 0; wait = 0; u = 0; v = 0; limit = 0
        }
        fun tick() {
            if (period == 0L) return
            if (trigger) return
            if (--wait != 0L) return
            var uu = u; var vv = v
            if ((uu and -uu) == vv) { uu++; vv = 1 } else vv *= 2
            var w = vv * period
            if (limited && w > limit) { uu = 1; vv = 1; w = period }
            trigger = true
            wait = w
            u = uu; v = vv
        }
        fun triggered(): Boolean {
            if (!trigger) return false
            trigger = false
            return true
        }
    }

    // =========================================================================
    // Clauses (value-faithful arena as parallel Int columns; ref = index). Binary
    // clauses are NOT stored here (watch-only), as in kissat.
    // =========================================================================
    private var cGlue = IntArray(64)
    private var cGarbage = BooleanArray(64)
    private var cReason = BooleanArray(64)
    private var cRedundant = BooleanArray(64)
    private var cUsed = IntArray(64)
    private var cSearched = IntArray(64)
    private var cSize = IntArray(64)
    private var cLits = arrayOfNulls<IntArray>(64)
    private var cCount = 0

    private fun cEnsure(cap: Int) {
        if (cap <= cGlue.size) return
        var n = cGlue.size
        while (n < cap) n *= 2
        cGlue = cGlue.copyOf(n)
        cGarbage = cGarbage.copyOf(n)
        cReason = cReason.copyOf(n)
        cRedundant = cRedundant.copyOf(n)
        cUsed = cUsed.copyOf(n)
        cSearched = cSearched.copyOf(n)
        cSize = cSize.copyOf(n)
        cLits = cLits.copyOf(n)
    }

    private fun allocateClause(litsIn: IntArray, redundant: Boolean, glue: Int): Int {
        cEnsure(cCount + 1)
        val ref = cCount++
        cGlue[ref] = if (glue < MAX_GLUE) glue else MAX_GLUE
        cGarbage[ref] = false
        cReason[ref] = false
        cRedundant[ref] = redundant
        cUsed[ref] = 0
        cSearched[ref] = 2
        cSize[ref] = litsIn.size
        cLits[ref] = litsIn.copyOf()
        return ref
    }
    private fun lits(ref: Int): IntArray = cLits[ref]!!

    // =========================================================================
    // Per-variable / per-literal state.
    // =========================================================================
    private var vars = 0
    private var values = ByteArray(2)            // indexed by literal (size 2*vars)
    private var aLevel = IntArray(1)
    private var aTrail = IntArray(1)
    private var aReason = IntArray(1) { DECISION_REASON }
    private var aBinary = BooleanArray(1)
    private var aAnalyzed = BooleanArray(1)
    private var aPoisoned = BooleanArray(1)
    private var aRemovable = BooleanArray(1)

    // trail
    private val trail = IntArrayList()
    private var propagate = 0
    private var level = 0
    private var unassigned = 0
    private var unflushed = 0

    // frames (index by level)
    private val fPromote = BooleanList()
    private val fDecision = IntArrayList()
    private val fTrail = IntArrayList()
    private val fUsed = IntArrayList()

    // watches: watches[lit] = growable Int list of watch words
    private var watches = arrayOfNulls<IntArrayList>(0)

    // scores heap (stable)
    private var score = DoubleArray(1)
    private var scinc = 1.0
    private val heapStack = IntArrayList()
    private var heapPos = IntArray(1) { DISCONTAIN }
    private var heapTainted = false

    // queue (focused)
    private var linkPrev = IntArray(1) { DISCONNECT }
    private var linkNext = IntArray(1) { DISCONNECT }
    private var linkStamp = IntArray(1)
    private var qFirst = DISCONNECT
    private var qLast = DISCONNECT
    private var qStamp = 0
    private var qSearchIdx = DISCONNECT
    private var qSearchStamp = 0

    // phases
    private var phaseSaved = ByteArray(1)
    private var phaseTarget = ByteArray(1)
    private var phaseBest = ByteArray(1)
    private var bestAssigned = 0
    private var targetAssigned = 0

    // analyze scratch
    private val analyzed = IntArrayList()
    private val levels = IntArrayList()
    private val clause = IntArrayList()
    private val shadow = IntArrayList()
    private val minimizeStk = IntArrayList()
    private val removableStk = IntArrayList()
    private val poisonedStk = IntArrayList()
    private val promoteStk = IntArrayList()

    // binary conflict pseudo-clause (ref == BIN_CONFLICT_REF sentinel)
    private val binConflictLits = IntArray(2)
    private val BIN_CONFLICT_REF = -3

    private var conflictRef = INVALID_REF

    // averages
    private val avgFastGlue = arrayOf(Smooth(), Smooth())
    private val avgSlowGlue = arrayOf(Smooth(), Smooth())
    private val avgInitialized = booleanArrayOf(false, false)

    private val reluctant = Reluctant()

    // tiers
    private val tier1arr = IntArray(2)
    private val tier2arr = IntArray(2)
    private val usedGlue = arrayOf(LongArray(MAX_GLUE_USED + 1), LongArray(MAX_GLUE_USED + 1))

    // limits / counters
    private var inconsistent = false
    private var iterating = false
    private var stable = false
    private var conflicts = 0L
    private var reductions = 0L
    private var restarts = 0L
    private var retiered = 0L
    private var limReduceConflicts = 0L
    private var limRestartConflicts = 0L
    private var limGlueConflicts = 0L
    private var limGlueInterval = 0L
    private var limModeConflicts = 0L
    private var modeCount = 0L

    // model (per idx)
    private var model = ByteArray(1)

    init {
        // root frame
        fPromote.add(false); fDecision.add(INVALID_LIT); fTrail.add(0); fUsed.add(0)
        val cap = maxOf(numVarsHint, 1)
        // pre-size some arrays lazily; vars created on demand
        ensureVarArrays(cap + 1)
        ensureWatchTables(2 * (cap + 1))
    }

    // =========================================================================
    // heap (inlineheap.h): max-heap by double score, POSITIONAL tie-break.
    // =========================================================================
    private fun heapContains(idx: Int): Boolean =
        idx < heapPos.size && heapPos[idx] != DISCONTAIN
    private fun getHeapScore(idx: Int): Double = if (idx < score.size) score[idx] else 0.0

    private fun bubbleUp(idx: Int) {
        var idxPos = heapPos[idx]
        val idxScore = score[idx]
        while (idxPos != 0) {
            val parentPos = (idxPos - 1) / 2
            val parent = heapStack[parentPos]
            if (score[parent] >= idxScore) break
            heapStack[idxPos] = parent
            heapPos[parent] = idxPos
            idxPos = parentPos
        }
        heapStack[idxPos] = idx
        heapPos[idx] = idxPos
    }
    private fun bubbleDown(idx: Int) {
        var idxPos = heapPos[idx]
        val end = heapStack.size
        val idxScore = score[idx]
        while (true) {
            var childPos = 2 * idxPos + 1
            if (childPos >= end) break
            var child = heapStack[childPos]
            var childScore = score[child]
            val siblingPos = childPos + 1
            if (siblingPos < end) {
                val sibling = heapStack[siblingPos]
                val siblingScore = score[sibling]
                if (siblingScore > childScore) {
                    child = sibling
                    childPos = siblingPos
                    childScore = siblingScore
                }
            }
            if (childScore <= idxScore) break
            heapStack[idxPos] = child
            heapPos[child] = idxPos
            idxPos = childPos
        }
        heapStack[idxPos] = idx
        heapPos[idx] = idxPos
    }
    private fun heapPush(idx: Int) {
        heapPos[idx] = heapStack.size
        heapStack.add(idx)
        bubbleUp(idx)
    }
    private fun heapMax(): Int = heapStack[0]
    private fun heapPopMax(): Int {
        val idx = heapStack[0]
        val last = heapStack[heapStack.size - 1]
        heapStack.removeLast()
        heapPos[last] = DISCONTAIN
        if (last == idx) return idx
        heapPos[idx] = DISCONTAIN
        heapStack[0] = last
        heapPos[last] = 0
        bubbleDown(last)
        return idx
    }
    private fun heapUpdate(idx: Int, newScore: Double) {
        val oldScore = getHeapScore(idx)
        if (oldScore == newScore) return
        score[idx] = newScore
        if (!heapTainted) heapTainted = true
        if (!heapContains(idx)) return
        if (newScore > oldScore) bubbleUp(idx) else bubbleDown(idx)
    }
    private fun maxScoreOnHeap(): Double {
        if (!heapTainted) return 0.0
        var res = score[0]
        for (i in 1 until vars) if (score[i] > res) res = score[i]
        return res
    }

    // =========================================================================
    // queue (inlinequeue.h)
    // =========================================================================
    private fun updateQueue(idx: Int) {
        qSearchIdx = idx
        qSearchStamp = linkStamp[idx]
    }
    private fun enqueueLinks(i: Int) {
        val j = qLast
        linkPrev[i] = j
        qLast = i
        if (j == DISCONNECT) qFirst = i else linkNext[j] = i
        linkStamp[i] = ++qStamp
    }
    private fun dequeueLinks(i: Int) {
        val j = linkPrev[i]; val k = linkNext[i]
        linkPrev[i] = DISCONNECT; linkNext[i] = DISCONNECT
        if (j == DISCONNECT) qFirst = k else linkNext[j] = k
        if (k == DISCONNECT) qLast = j else linkPrev[k] = j
    }
    private fun enqueue(idx: Int) {
        linkPrev[idx] = DISCONNECT; linkNext[idx] = DISCONNECT
        enqueueLinks(idx)
        if (values[lit(idx)].toInt() == 0) updateQueue(idx)
    }
    private fun moveToFront(idx: Int) {
        if (idx == qLast) return
        val tmp = values[lit(idx)].toInt()
        if (tmp != 0 && qSearchIdx == idx) {
            val prev = linkPrev[idx]
            if (prev != DISCONNECT) updateQueue(prev)
            else { val next = linkNext[idx]; updateQueue(next) }
        }
        dequeueLinks(idx)
        enqueueLinks(idx)
        if (tmp == 0) updateQueue(idx)
    }

    // =========================================================================
    // new variable (lazy)
    // =========================================================================
    private fun ensureVarArrays(cap: Int) {
        if (aLevel.size >= cap) return
        var n = aLevel.size
        while (n < cap) n *= 2
        values = values.copyOf(2 * n)
        aLevel = aLevel.copyOf(n)
        aTrail = aTrail.copyOf(n)
        aReason = aReason.copyOf(n).also { for (i in aReason.size until n) it[i] = DECISION_REASON }
        aBinary = aBinary.copyOf(n)
        aAnalyzed = aAnalyzed.copyOf(n)
        aPoisoned = aPoisoned.copyOf(n)
        aRemovable = aRemovable.copyOf(n)
        score = score.copyOf(n)
        heapPos = heapPos.copyOf(n).also { for (i in heapPos.size until n) it[i] = DISCONTAIN }
        linkPrev = linkPrev.copyOf(n).also { for (i in linkPrev.size until n) it[i] = DISCONNECT }
        linkNext = linkNext.copyOf(n).also { for (i in linkNext.size until n) it[i] = DISCONNECT }
        linkStamp = linkStamp.copyOf(n)
        phaseSaved = phaseSaved.copyOf(n)
        phaseTarget = phaseTarget.copyOf(n)
        phaseBest = phaseBest.copyOf(n)
    }
    private fun ensureWatchTables(size: Int) {
        if (watches.size >= size) return
        val nw = arrayOfNulls<IntArrayList>(size)
        for (i in watches.indices) nw[i] = watches[i]
        for (i in watches.size until size) nw[i] = IntArrayList()
        watches = nw
    }
    private fun w(lit: Int): IntArrayList = watches[lit]!!

    fun ensureVars(needed: Int) {
        while (vars < needed) {
            val idx = vars++
            ensureVarArrays(vars + 1)
            ensureWatchTables(2 * (vars + 1))
            values[lit(idx)] = 0; values[not(lit(idx))] = 0
            aLevel[idx] = 0; aTrail[idx] = 0; aReason[idx] = DECISION_REASON
            aBinary[idx] = false; aAnalyzed[idx] = false; aPoisoned[idx] = false; aRemovable[idx] = false
            score[idx] = 0.0; heapPos[idx] = DISCONTAIN
            linkPrev[idx] = DISCONNECT; linkNext[idx] = DISCONNECT; linkStamp[idx] = 0
            phaseSaved[idx] = 0; phaseTarget[idx] = 0; phaseBest[idx] = 0
            unassigned++
            enqueue(idx)
            heapPush(idx)
            numVars = vars
        }
    }

    // =========================================================================
    // watch words (flat Int list). binary = 1 word ((lit<<1)|1). large = 2 words
    // (blocking-word (lit<<1) then raw ref).
    // =========================================================================
    private fun binaryWatchWord(l: Int): Int = (l shl 1) or 1
    private fun blockingWatchWord(l: Int): Int = l shl 1
    private fun watchIsBinary(word: Int): Boolean = (word and 1) != 0
    private fun watchLit(word: Int): Int = word ushr 1

    private fun watchBinary(a: Int, b: Int) {
        w(a).add(binaryWatchWord(b))
        w(b).add(binaryWatchWord(a))
    }
    private fun watchBlocking(l: Int, blocking: Int, ref: Int) {
        w(l).add(blockingWatchWord(blocking))
        w(l).add(ref)
    }
    private fun watchReference(a: Int, b: Int, ref: Int) {
        watchBlocking(a, b, ref)
        watchBlocking(b, a, ref)
    }
    private fun unwatchBlocking(l: Int, ref: Int) {
        val ws = w(l)
        val keep = IntArrayList()
        var i = 0
        while (i < ws.size) {
            val word = ws[i]
            if (watchIsBinary(word)) { keep.add(word); i++; continue }
            val r = ws[i + 1]
            if (r == ref) { i += 2; continue }
            keep.add(word); keep.add(r); i += 2
        }
        ws.clear()
        for (t in 0 until keep.size) ws.add(keep[t])
    }

    // =========================================================================
    // clause creation
    // =========================================================================
    private fun newOriginalClause(litsIn: IntArray): Boolean {
        if (litsIn.isEmpty()) { inconsistent = true; return false }
        if (litsIn.size == 1) { assignUnit(litsIn[0], UNIT_REASON); return true }
        if (litsIn.size == 2) { watchBinary(litsIn[0], litsIn[1]); return true }
        val ref = allocateClause(litsIn, false, 0)
        watchReference(lits(ref)[0], lits(ref)[1], ref)
        return true
    }
    // build clause from solver 'clause' stack
    private fun newRedundantClause(glue: Int): Int {
        val size = clause.size
        if (size == 2) { watchBinary(clause[0], clause[1]); return INVALID_REF }
        val ref = allocateClause(clause.toArray(), true, glue)
        watchReference(lits(ref)[0], lits(ref)[1], ref)
        return ref
    }

    // =========================================================================
    // glue recompute / promote / mark-as-used (promote.h / deduce.c)
    // =========================================================================
    private fun recomputeGlue(ref: Int, limit: Int): Int {
        var res = 0
        promoteStk.clear()
        for (l in lits(ref)) {
            val lv = aLevel[idxOf(l)]
            if (fPromote[lv]) continue
            res++
            if (res == limit) break
            fPromote[lv] = true
            promoteStk.add(lv)
        }
        for (t in 0 until promoteStk.size) fPromote[promoteStk[t]] = false
        promoteStk.clear()
        return res
    }
    private fun promoteClause(ref: Int, newGlue: Int) { cGlue[ref] = newGlue }
    private fun markClauseAsUsed(ref: Int) {
        if (!cRedundant[ref]) return
        cUsed[ref] = MAX_USED
        val oldGlue = cGlue[ref]
        val newGlue = recomputeGlue(ref, oldGlue)
        if (newGlue < oldGlue) promoteClause(ref, newGlue)
        val g = minOf(cGlue[ref], MAX_GLUE_USED)
        usedGlue[if (stable) 1 else 0][g]++
    }

    // =========================================================================
    // assign (inlineassign.h)
    // =========================================================================
    private fun doAssign(levelIn: Int, binary: Boolean, l: Int, reasonIn: Int) {
        val notLit = not(l)
        values[l] = 1
        values[notLit] = -1
        unassigned--
        var reason = reasonIn
        var bin = binary
        val litLevel = levelIn
        if (litLevel == 0) {
            unflushed++
            if (reason != UNIT_REASON) { reason = UNIT_REASON; bin = false }
        }
        val t = trail.size
        trail.add(l)
        val idx = idxOf(l)
        phaseSaved[idx] = if (negated(l)) (-1).toByte() else 1.toByte()
        aLevel[idx] = litLevel
        aTrail[idx] = t
        aAnalyzed[idx] = false
        aBinary[idx] = bin
        aPoisoned[idx] = false
        aReason[idx] = reason
        aRemovable[idx] = false
    }
    private fun assignmentLevel(l: Int, ref: Int): Int {
        var res = 0
        for (other in lits(ref)) {
            if (other == l) continue
            val lv = aLevel[idxOf(other)]
            if (res < lv) res = lv
        }
        return res
    }
    private fun assignDecision(l: Int) {
        doAssign(level, false, l, DECISION_REASON)
        tr("ASSIGN ${dimacs(l)} reason=D")
    }
    private fun assignBinary(l: Int, other: Int) {
        val lv = aLevel[idxOf(other)]
        doAssign(lv, true, l, other)
        tr("ASSIGN ${dimacs(l)} reason=C")
    }
    private fun assignReference(l: Int, ref: Int) {
        val lv = assignmentLevel(l, ref)
        doAssign(lv, false, l, ref)
        tr("ASSIGN ${dimacs(l)} reason=C")
    }
    private fun assignUnit(l: Int, reason: Int) {
        doAssign(0, false, l, reason)
        tr("ASSIGN ${dimacs(l)} reason=U")
    }
    private fun learnedUnit(l: Int) = assignUnit(l, UNIT_REASON)

    private fun pushFrame(decision: Int) {
        fDecision.add(decision); fPromote.add(false); fTrail.add(trail.size); fUsed.add(0)
    }

    // =========================================================================
    // propagate one literal (proplit.h)
    // =========================================================================
    private fun propagateLiteral(l: Int): Int {
        val notLit = not(l)
        val ws = w(notLit)
        var res = INVALID_REF
        var p = 0; var q = 0
        val n = ws.size
        while (p != n) {
            val head = ws[p]; ws[q] = head; q++; p++
            val blocking = watchLit(head)
            val blockingValue = values[blocking].toInt()
            val binary = watchIsBinary(head)
            var tail = 0
            if (!binary) { tail = ws[p]; ws[q] = tail; q++; p++ }
            if (blockingValue > 0) continue
            if (binary) {
                if (blockingValue < 0) {
                    binConflictLits[0] = notLit; binConflictLits[1] = blocking
                    res = BIN_CONFLICT_REF
                    break
                } else {
                    assignBinary(blocking, notLit)
                }
            } else {
                val ref = tail
                if (cGarbage[ref]) { q -= 2; continue }
                val ls = lits(ref)
                val other = ls[0] xor ls[1] xor notLit
                val otherValue = values[other].toInt()
                if (otherValue > 0) {
                    ws[q - 2] = blockingWatchWord(other)
                    continue
                }
                val size = cSize[ref]
                var r: Int
                var replacement = INVALID_LIT
                var replacementValue = -1
                r = cSearched[ref]
                while (r != size) {
                    replacement = ls[r]
                    replacementValue = values[replacement].toInt()
                    if (replacementValue >= 0) break
                    r++
                }
                if (replacementValue < 0) {
                    r = 2
                    while (r != cSearched[ref]) {
                        replacement = ls[r]
                        replacementValue = values[replacement].toInt()
                        if (replacementValue >= 0) break
                        r++
                    }
                }
                if (replacementValue >= 0) {
                    cSearched[ref] = r
                    q -= 2
                    ls[0] = other
                    ls[1] = replacement
                    ls[r] = notLit
                    watchBlocking(replacement, other, ref)
                } else if (otherValue != 0) {
                    res = ref
                    break
                } else {
                    assignReference(other, ref)
                }
            }
        }
        while (p != n) { ws[q] = ws[p]; q++; p++ }
        ws.shrinkTo(q)
        return res
    }

    private fun searchPropagate(): Int {
        var res = INVALID_REF
        while (res == INVALID_REF && propagate != trail.size)
            res = propagateLiteral(trail[propagate++])
        if (res != INVALID_REF) {
            conflicts++
            if (level == 0) inconsistent = true
        } else if (level == 0 && unflushed != 0) {
            flushTrail()
        }
        return res
    }
    private fun flushTrail() { trail.clear(); propagate = 0; unflushed = 0 }

    // helper: literals of a conflict ref (handles binary pseudo-clause)
    private fun conflictLits(ref: Int): IntArray = if (ref == BIN_CONFLICT_REF) binConflictLits else lits(ref)
    private fun conflictSize(ref: Int): Int = if (ref == BIN_CONFLICT_REF) 2 else cSize[ref]

    // =========================================================================
    // backtrack (backtrack.c) with reuse
    // =========================================================================
    private fun unassign(l: Int) {
        val notLit = not(l)
        values[l] = 0; values[notLit] = 0
        unassigned++
    }
    private fun backtrackWithoutUpdatingPhases(newLevel: Int) {
        if (level == newLevel) return
        val newTrail = fTrail[newLevel + 1]
        // frames.resize(newLevel + 1)
        fDecision.shrinkTo(newLevel + 1); fPromote.shrinkTo(newLevel + 1)
        fTrail.shrinkTo(newLevel + 1); fUsed.shrinkTo(newLevel + 1)
        val oldEnd = trail.size
        var qi = newTrail
        if (stable) {
            var pi = newTrail
            while (pi != oldEnd) {
                val l = trail[pi]
                val idx = idxOf(l)
                val lv = aLevel[idx]
                if (lv <= newLevel) {
                    aTrail[idx] = qi
                    trail[qi] = l; qi++
                } else {
                    unassign(l)
                    if (!heapContains(idx)) heapPush(idx)
                }
                pi++
            }
        } else {
            var pi = newTrail
            while (pi != oldEnd) {
                val l = trail[pi]
                val idx = idxOf(l)
                val lv = aLevel[idx]
                if (lv <= newLevel) {
                    aTrail[idx] = qi
                    trail[qi] = l; qi++
                } else {
                    unassign(l)
                    if (linkStamp[idx] > qSearchStamp) updateQueue(idx)
                }
                pi++
            }
        }
        trail.shrinkTo(qi)
        level = newLevel
        propagate = newTrail
    }
    private fun updateTargetAndBest() {
        if (!stable) return
        val assigned = vars - unassigned
        if (targetAssigned < assigned) {
            targetAssigned = assigned
            for (i in 0 until vars) { val tmp = phaseSaved[i]; if (tmp.toInt() != 0) phaseTarget[i] = tmp }
        }
        if (bestAssigned < assigned) {
            bestAssigned = assigned
            for (i in 0 until vars) { val tmp = phaseSaved[i]; if (tmp.toInt() != 0) phaseBest[i] = tmp }
        }
    }
    private fun backtrackInConsistentState(newLevel: Int) {
        updateTargetAndBest()
        backtrackWithoutUpdatingPhases(newLevel)
    }
    private fun backtrackAfterConflict(newLevel: Int) {
        if (level != 0) backtrackWithoutUpdatingPhases(level - 1)
        updateTargetAndBest()
        backtrackWithoutUpdatingPhases(newLevel)
    }
    private fun determineNewLevel(jump: Int): Int = jump   // chrono OFF

    // =========================================================================
    // analyze helpers (deduce.c)
    // =========================================================================
    private fun pushAnalyzed(idx: Int) { aAnalyzed[idx] = true; analyzed.add(idx) }
    private fun analyzeLiteral(l: Int): Boolean {
        val idx = idxOf(l)
        val lv = aLevel[idx]
        if (lv == 0) return false
        if (aAnalyzed[idx]) return false
        pushAnalyzed(idx)
        if (lv == level) return true
        clause.add(l)
        val used = fUsed[lv]
        fUsed[lv] = used + 1
        if (used != 0) return false
        levels.add(lv)
        return false
    }

    private fun deduceFirstUipClause(conflictRefIn: Int) {
        val cref = conflictRefIn
        if (conflictSize(cref) > 2 && cref != BIN_CONFLICT_REF) markClauseAsUsed(cref)
        clause.clear()
        clause.add(INVALID_LIT)
        var unresolved = 0
        for (l in conflictLits(cref)) if (analyzeLiteral(l)) unresolved++
        var tpos = trail.size
        var uip = INVALID_LIT
        while (true) {
            var idx: Int
            do {
                uip = trail[--tpos]
                idx = idxOf(uip)
            } while (!aAnalyzed[idx] || aLevel[idx] != level)
            if (unresolved == 1) break
            idx = idxOf(uip)
            if (aBinary[idx]) {
                val other = aReason[idx]
                if (analyzeLiteral(other)) unresolved++
            } else {
                val ref = aReason[idx]
                for (l in lits(ref)) if (l != uip && analyzeLiteral(l)) unresolved++
                markClauseAsUsed(ref)
            }
            unresolved--
        }
        clause[0] = not(uip)
    }

    // sort_deduced_clause (analyze.c): learned clause laid out highest-level-first
    private fun sortDeducedClause() {
        // sort levels ascending
        run {
            val a = levels.toArray()
            a.sort()
            levels.clear()
            for (x in a) levels.add(x)
        }
        var pos = 1
        var i = levels.size - 1
        while (i >= 0) {
            val lv = levels[i]
            val used = fUsed[lv]
            fUsed[lv] = pos
            pos += used
            i--
        }
        val sizeClause = clause.size
        while (shadow.size < sizeClause) shadow.add(INVALID_LIT)
        shadow[0] = clause[0]
        for (k in 1 until clause.size) {
            val l = clause[k]
            val lv = aLevel[idxOf(l)]
            val p = fUsed[lv]
            fUsed[lv] = p + 1
            shadow[p] = l
        }
        for (k in 0 until sizeClause) clause[k] = shadow[k]
        // restore per-level counts
        pos = 1
        i = levels.size - 1
        while (i >= 0) {
            val lv = levels[i]
            val end = fUsed[lv]
            fUsed[lv] = end - pos
            pos = end
            i--
        }
        shadow.clear()
    }

    // =========================================================================
    // minimize (minimize.c)
    // =========================================================================
    private fun minimizedIndex(minimizing: Boolean, idx: Int, depth: Int): Int {
        if (aLevel[idx] == 0) return 1
        if (aRemovable[idx] && depth != 0) return 1
        if (aReason[idx] == DECISION_REASON) return -1
        if (aPoisoned[idx]) return -1
        if (minimizing || depth == 0) {
            if (fUsed[aLevel[idx]] <= 1) return -1
        }
        return 0
    }
    private fun minimizeReference(minimizing: Boolean, ref: Int, l: Int, depth: Int): Boolean {
        val nextDepth = if (depth == -1) depth else depth + 1  // -1 stands in for UINT_MAX; never hit here
        val notLit = not(l)
        for (other in lits(ref))
            if (other != notLit && !minimizeLiteral(minimizing, other, nextDepth)) return false
        return true
    }
    private fun minimizeBinary(minimizing: Boolean, l: Int, depth: Int): Boolean {
        val saved = minimizeStk.size
        var res: Boolean
        var next = l
        while (true) {
            val nextIdx = idxOf(next)
            val tmp = minimizedIndex(minimizing, nextIdx, 1)
            if (tmp != 0) { res = tmp > 0; break }
            minimizeStk.add(nextIdx)
            if (!aBinary[nextIdx]) {
                val nextDepth = if (depth == -1) depth else depth + 1
                res = minimizeReference(minimizing, aReason[nextIdx], next, nextDepth)
                break
            }
            next = aReason[nextIdx]
        }
        var t = saved
        while (t < minimizeStk.size) {
            val idx = minimizeStk[t]
            if (res) { if (!aRemovable[idx]) { aRemovable[idx] = true; removableStk.add(idx) } }
            else { if (!aPoisoned[idx]) { aPoisoned[idx] = true; poisonedStk.add(idx) } }
            t++
        }
        minimizeStk.shrinkTo(saved)
        return res
    }
    private fun minimizeLiteral(minimizing: Boolean, l: Int, depth: Int): Boolean {
        if (depth >= OPT_minimizedepth) return false
        val idx = idxOf(l)
        val tmp = minimizedIndex(minimizing, idx, depth)
        if (tmp > 0) return true
        if (tmp < 0) return false
        val res: Boolean
        if (aBinary[idx]) {
            val other = aReason[idx]
            res = minimizeBinary(minimizing, other, depth)
        } else {
            val ref = aReason[idx]
            res = minimizeReference(minimizing, ref, l, depth)
        }
        if (depth == 0) return res
        if (!res) { if (!aPoisoned[idx]) { aPoisoned[idx] = true; poisonedStk.add(idx) } }
        else if (!aRemovable[idx]) { aRemovable[idx] = true; removableStk.add(idx) }
        return res
    }
    private fun minimizeClause() {
        for (t in 0 until clause.size) {
            val idx = idxOf(clause[t])
            if (!aRemovable[idx]) { aRemovable[idx] = true; removableStk.add(idx) }
        }
        val n = clause.size
        var p = n - 1
        while (p > 0) {
            val l = clause[p]
            if (minimizeLiteral(true, l, 0)) clause[p] = INVALID_LIT
            p--
        }
        var q = 0
        for (i in 0 until n) { val l = clause[i]; if (l != INVALID_LIT) { clause[q] = l; q++ } }
        clause.shrinkTo(q)
        for (t in 0 until poisonedStk.size) aPoisoned[poisonedStk[t]] = false
        poisonedStk.clear()
        for (t in 0 until removableStk.size) aRemovable[removableStk[t]] = false
        removableStk.clear()
    }

    // =========================================================================
    // bump (bump.c)
    // =========================================================================
    private fun rescaleScores() {
        val maxScore = maxScoreOnHeap()
        val rescale = maxOf(maxScore, scinc)
        val factor = 1.0 / rescale
        for (i in 0 until vars) score[i] = scoreRound(score[i] * factor)
        scinc *= factor
    }
    private fun bumpScoreIncrement() {
        val oldScinc = scinc
        val decay = OPT_decay * 1e-3
        val factor = 1.0 / (1.0 - decay)
        val newScinc = oldScinc * factor
        scinc = newScinc
        if (newScinc > MAX_SCORE) rescaleScores()
    }
    private fun bumpVariableScore(idx: Int) {
        val oldScore = getHeapScore(idx)
        val inc = scinc
        val newScore = scoreRound(oldScore + inc)
        heapUpdate(idx, newScore)
        if (newScore > MAX_SCORE) rescaleScores()
    }
    private fun bumpAnalyzed() {
        if (!stable) {
            val order = analyzed.toArray()
            stableSortByStamp(order)
            for (idx in order) moveToFront(idx)
        } else {
            for (t in 0 until analyzed.size) bumpVariableScore(analyzed[t])
            bumpScoreIncrement()
        }
    }
    /** Stable insertion sort by ascending link stamp (matches kissat's radix/quick stable bump order). */
    private fun stableSortByStamp(a: IntArray) {
        for (i in 1 until a.size) {
            val key = a[i]; val ks = linkStamp[key]
            var j = i - 1
            while (j >= 0 && linkStamp[a[j]] > ks) { a[j + 1] = a[j]; j-- }
            a[j + 1] = key
        }
    }

    // =========================================================================
    // learn (learn.c)
    // =========================================================================
    private fun updateLearned(glue: Int) {
        if (stable) reluctant.tick()
        avgFastGlue[if (stable) 1 else 0].update(glue.toDouble())
        avgSlowGlue[if (stable) 1 else 0].update(glue.toDouble())
    }
    private fun learnUnit(notUip: Int) {
        val newLevel = determineNewLevel(0)
        backtrackAfterConflict(newLevel)
        learnedUnit(notUip)
        iterating = true
    }
    private fun learnBinary(notUip: Int) {
        val other = clause[1]
        val jumpLevel = aLevel[idxOf(other)]
        val newLevel = determineNewLevel(jumpLevel)
        backtrackAfterConflict(newLevel)
        newRedundantClause(1)
        assignBinary(notUip, other)
    }
    private fun learnReference(notUip: Int, glue: Int) {
        val ls = clause
        var qi = 1
        var jumpLit = ls[1]
        var jumpLevel = aLevel[idxOf(jumpLit)]
        val end = ls.size
        val backtrackLevel = level - 1
        var p = 2
        while (p != end) {
            val l = ls[p]
            val lv = aLevel[idxOf(l)]
            if (jumpLevel >= lv) { p++; continue }
            jumpLevel = lv; jumpLit = l; qi = p
            if (lv == backtrackLevel) break
            p++
        }
        ls[qi] = ls[1]
        ls[1] = jumpLit
        val ref = newRedundantClause(glue)
        cUsed[ref] = MAX_USED
        val newLevel = determineNewLevel(jumpLevel)
        backtrackAfterConflict(newLevel)
        assignReference(notUip, ref)
    }
    private fun learnClause() {
        val notUip = clause[0]
        val size = clause.size
        val glue = levels.size
        updateLearned(glue)
        when (size) {
            1 -> learnUnit(notUip)
            2 -> learnBinary(notUip)
            else -> learnReference(notUip, glue)
        }
    }

    // =========================================================================
    // reset analyzed / analysis
    // =========================================================================
    private fun resetOnlyAnalyzedLiterals() {
        for (t in 0 until analyzed.size) aAnalyzed[analyzed[t]] = false
        analyzed.clear()
    }
    private fun resetLevels() {
        for (t in 0 until levels.size) fUsed[levels[t]] = 0
        levels.clear()
    }
    private fun resetAnalysisButNotAnalyzedLiterals() {
        resetLevels()
        clause.clear()
    }

    // =========================================================================
    // one_literal_on_conflict_level (analyze.c)
    // =========================================================================
    private var ollConflictLevel = 0
    private fun oneLiteralOnConflictLevel(cref: Int): Boolean {
        var jumpLevel = INVALID_LEVEL
        var conflictLevel = INVALID_LEVEL
        var literalsOnConflictLevel = 0
        var forcedLit = INVALID_LIT
        val ls = conflictLits(cref)
        val conflictSz = conflictSize(cref)
        var i = 0
        while (i < conflictSz) {
            val l = ls[i]
            val litLevel = aLevel[idxOf(l)]
            if (conflictLevel == INVALID_LEVEL || levelLess(conflictLevel, litLevel)) {
                literalsOnConflictLevel = 1
                jumpLevel = conflictLevel
                conflictLevel = litLevel
                forcedLit = l
            } else {
                if (jumpLevel == INVALID_LEVEL || levelLess(jumpLevel, litLevel)) jumpLevel = litLevel
                if (conflictLevel == litLevel) literalsOnConflictLevel++
            }
            if (literalsOnConflictLevel > 1 && conflictLevel == level) break
            i++
        }
        ollConflictLevel = conflictLevel

        if (conflictLevel == 0) { inconsistent = true; return false }

        if (conflictLevel < level) backtrackAfterConflict(conflictLevel)

        if (conflictSz > 2) {
            var ii = 0
            while (ii < 2) {
                val l = ls[ii]
                var highestPosition = ii
                var highestLiteral = l
                var highestLevel = aLevel[idxOf(l)]
                var j = ii + 1
                while (j < conflictSz) {
                    val other = ls[j]
                    val lv = aLevel[idxOf(other)]
                    if (highestLevel >= lv) { j++; continue }
                    highestLiteral = other
                    highestPosition = j
                    highestLevel = lv
                    if (highestLevel == conflictLevel) break
                    j++
                }
                if (highestPosition == ii) { ii++; continue }
                var ref = INVALID_REF
                if (highestPosition > 1) {
                    ref = cref
                    unwatchBlocking(l, ref)
                }
                ls[highestPosition] = l
                ls[ii] = highestLiteral
                if (highestPosition > 1) watchBlocking(ls[ii], ls[if (ii == 0) 1 else 0], ref)
                ii++
            }
        }

        if (literalsOnConflictLevel > 1) return false

        val newLevel = determineNewLevel(jumpLevel)
        backtrackAfterConflict(newLevel)

        if (conflictSz == 2) {
            val other = ls[0] xor ls[1] xor forcedLit
            assignBinary(forcedLit, other)
        } else {
            assignReference(forcedLit, cref)
        }
        return true
    }
    // unsigned-safe "a < b" for levels where INVALID_LEVEL(-1) is UINT_MAX (largest).
    // In the conflict-level scan the comparands are always real levels (<= solver level),
    // so plain signed < is correct EXCEPT when conflictLevel/jumpLevel is still
    // INVALID_LEVEL; those cases are guarded separately above, so signed compare is safe.
    private fun levelLess(a: Int, b: Int): Boolean = a < b

    // =========================================================================
    // tiers (tiers.c)
    // =========================================================================
    private fun computeTierLimits(st: Boolean): IntArray {
        val u = usedGlue[if (st) 1 else 0]
        var total = 0L
        for (g in 0..MAX_GLUE_USED) total += u[g]
        var t1 = -1; var t2 = -1
        if (total != 0L) {
            val lim1 = (total * 0.5).toLong()
            val lim2 = (total * 0.9).toLong()
            var acc = 0L
            var g = 0
            while (g <= MAX_GLUE_USED) { acc += u[g]; if (acc >= lim1) { t1 = g; break }; g++ }
            if (acc < lim2) {
                g = t1 + 1
                while (g <= MAX_GLUE_USED) { acc += u[g]; if (acc >= lim2) { t2 = g; break }; g++ }
            }
        }
        if (t1 < 0) { t1 = OPT_tier1; t2 = maxOf(OPT_tier2, OPT_tier1) }
        else if (t2 < 0) t2 = t1
        return intArrayOf(t1, t2)
    }
    private fun computeAndSetTierLimits() {
        val r = computeTierLimits(stable)
        tier1arr[if (stable) 1 else 0] = r[0]
        tier2arr[if (stable) 1 else 0] = r[1]
    }
    private fun tier1(): Int = tier1arr[0]
    private fun tier2(): Int = tier2arr[1]

    // =========================================================================
    // reduce (reduce.c)
    // =========================================================================
    private fun reducing(): Boolean {
        if (OPT_reduce == 0) return false
        var any = false
        for (ref in 0 until cCount) if (cRedundant[ref] && !cGarbage[ref]) { any = true; break }
        if (!any) return false
        return conflicts >= limReduceConflicts
    }
    private fun markReasonClauses() {
        for (t in 0 until trail.size) {
            val idx = idxOf(trail[t])
            if (aBinary[idx]) continue
            val ref = aReason[idx]
            if (ref == UNIT_REASON || ref == DECISION_REASON) continue
            cReason[ref] = true
        }
    }
    private fun unmarkReasonClauses() {
        for (ref in 0 until cCount) cReason[ref] = false
    }
    private fun reduce(): Int {
        reductions++
        computeAndSetTierLimits()
        markReasonClauses()

        val t1 = tier1()
        val t2 = maxOf(t1, tier2())

        // collect (rank, ref)
        val ranks = ArrayList<Long>()
        val refs = ArrayList<Int>()
        for (ref in 0 until cCount) {
            if (!cRedundant[ref]) continue
            if (cGarbage[ref]) continue
            val used = cUsed[ref]
            if (used != 0) cUsed[ref] = used - 1
            if (cReason[ref]) continue
            val glue = cGlue[ref]
            if (glue <= t1 && used != 0) continue
            if (glue <= t2 && used >= MAX_USED - 1) continue
            val negativeSize = (cSize[ref].toLong().inv()) and 0xffffffffL
            val negativeGlue = (cGlue[ref].toLong().inv()) and 0xffffffffL
            val rank = negativeSize or (negativeGlue shl 32)
            ranks.add(rank); refs.add(ref)
        }
        if (ranks.isNotEmpty()) {
            // stable index sort by rank ascending (total order on packed uint64)
            val order = (0 until ranks.size).sortedBy { ranks[it] }
            val high = OPT_reducehigh * 0.1
            val low = OPT_reducelow * 0.1
            val percent: Double = if (low < high) {
                val delta = high - low
                high - delta / kotlin.math.log10(reductions.toDouble() + 9)
            } else low
            val fraction = percent / 100.0
            val size = ranks.size
            var target = (size * fraction).toLong()
            var i = 0
            while (i < order.size && target > 0) {
                markClauseAsGarbage(refs[order[i]])
                i++; target--
            }
        }
        sparseCollect()
        unmarkReasonClauses()

        var delta = (gReduceInt * kotlin.math.sqrt(reductions.toDouble())).toLong()
        if (delta < 1) delta = 1
        limReduceConflicts = conflicts + delta
        tr("REDUCE")
        return if (inconsistent) 20 else 0
    }
    private fun markClauseAsGarbage(ref: Int) {
        if (cGarbage[ref]) return
        cGarbage[ref] = true
        unwatchBlocking(lits(ref)[0], ref)
        unwatchBlocking(lits(ref)[1], ref)
    }
    private fun sparseCollect() {
        // drop garbage clauses (references stay stable; only unwatched/garbage-marked).
        // Value-faithfulness: a garbage clause is simply left marked; live refs never
        // point into it (reasons are protected before reduce). We keep the slot to keep
        // refs stable (matches the C reference).
    }

    // =========================================================================
    // restart (restart.c)
    // =========================================================================
    private fun restarting(): Boolean {
        if (OPT_restart == 0) return false
        if (level == 0) return false
        if (conflicts < limRestartConflicts) return false
        if (stable) return reluctant.triggered()
        val fast = avgFastGlue[0].value
        val slow = avgSlowGlue[0].value
        val margin = (100.0 + OPT_restartmargin) / 100.0
        val limit = margin * slow
        return limit <= fast
    }
    private fun updateFocusedRestartLimit() {
        var delta = OPT_restartint.toLong()
        if (restarts != 0L) delta += logn(restarts).toLong() - 1
        limRestartConflicts = conflicts + delta
    }
    private fun reuseStableTrail(): Int {
        val nextIdx = nextDecisionVariable()
        val limit = getHeapScore(nextIdx)
        var res = 0
        while (res < level) {
            val idx = idxOf(fDecision[res + 1])
            val s = getHeapScore(idx)
            if (s <= limit) break
            res++
        }
        return res
    }
    private fun reuseFocusedTrail(): Int {
        val nextIdx = nextDecisionVariable()
        val limit = linkStamp[nextIdx]
        var res = 0
        while (res < level) {
            val idx = idxOf(fDecision[res + 1])
            val s = linkStamp[idx]
            if (s <= limit) break
            res++
        }
        return res
    }
    private fun reuseTrail(): Int = if (stable) reuseStableTrail() else reuseFocusedTrail()
    private fun restart() {
        restarts++
        val lv = reuseTrail()
        backtrackInConsistentState(lv)
        if (!stable) updateFocusedRestartLimit()
        tr("RESTART")
    }

    // =========================================================================
    // stabilize mode (mode.c switch_search_mode) -- simplified conflict schedule.
    // =========================================================================
    private fun logn(count: Long): Double = kotlin.math.log10(count.toDouble() + 9)
    private fun switchingSearchMode(): Boolean = conflicts >= limModeConflicts
    private fun initModeLimit() {
        modeCount++
        var delta = gStableInit
        val sp = modeCount
        delta *= sp * sp
        limModeConflicts = conflicts + delta
    }
    private fun initAverages() {
        val s = if (stable) 1 else 0
        if (avgInitialized[s]) return
        avgFastGlue[s].init(OPT_emafast)
        avgSlowGlue[s].init(OPT_emaslow)
        avgInitialized[s] = true
    }
    private fun updateScores() {
        for (idx in 0 until vars) if (!heapContains(idx)) heapPush(idx)
    }
    private fun switchSearchMode() {
        stable = !stable
        initAverages()
        if (stable) {
            reluctant.enable(OPT_reluctantint, OPT_reluctantlim)
            updateScores()
        } else {
            reluctant.disable()
            updateFocusedRestartLimit()
        }
        initModeLimit()
    }

    // =========================================================================
    // decide (decide.c)
    // =========================================================================
    private fun lastEnqueuedUnassignedVariable(): Int {
        var res = qSearchIdx
        if (values[lit(res)].toInt() != 0) {
            do { res = linkPrev[res] } while (values[lit(res)].toInt() != 0)
            updateQueue(res)
        }
        return res
    }
    private fun largestScoreUnassignedVariable(): Int {
        var res = heapMax()
        while (values[lit(res)].toInt() != 0) {
            heapPopMax()
            res = heapMax()
        }
        return res
    }
    private fun nextDecisionVariable(): Int =
        if (stable) largestScoreUnassignedVariable() else lastEnqueuedUnassignedVariable()
    private fun decidePhase(idx: Int): Int {
        var res = 0
        val useTarget = OPT_target != 0 && (stable || OPT_target > 1)
        if (res == 0 && useTarget) res = phaseTarget[idx].toInt()
        if (res == 0 && OPT_phasesaving != 0) res = phaseSaved[idx].toInt()
        if (res == 0) res = initialPhase()
        return if (res < 0) -1 else 1
    }
    private fun decide() {
        level++
        val idx = nextDecisionVariable()
        val v = decidePhase(idx)
        var l = lit(idx)
        if (v < 0) l = not(l)
        pushFrame(l)
        tr("DECIDE ${dimacs(l)}")
        assignDecision(l)
    }

    // =========================================================================
    // analyze (analyze.c)
    // =========================================================================
    private fun analyze(conflictRefIn: Int): Int {
        if (inconsistent) return 20
        var cref = conflictRefIn
        var res: Int
        do {
            if (oneLiteralOnConflictLevel(cref)) res = 1
            else if (ollConflictLevel == 0) res = -1
            else if (ollConflictLevel == 1) { analyzeFailedLiteral(cref); res = 1 }
            else {
                deduceFirstUipClause(cref)
                if (OPT_minimize != 0) {
                    sortDeducedClause()
                    minimizeClause()
                }
                learnClause()
                resetAnalysisButNotAnalyzedLiterals()
                res = 1
            }
            if (analyzed.size != 0) {
                if (OPT_bump != 0) bumpAnalyzed()
                resetOnlyAnalyzedLiterals()
            }
        } while (res == 0)
        return if (res > 0) 0 else 20
    }

    private fun analyzeFailedLiteral(cref: Int) {
        val failed = fDecision[1]
        val units = IntArrayList()
        val notFailed = not(failed)
        var tpos = trail.size
        var unresolved = 0
        var unit = INVALID_LIT
        var done = false
        for (l in conflictLits(cref)) {
            if (l == notFailed) { done = true; break }
            val idx = idxOf(l)
            if (aLevel[idx] == 0) continue
            pushAnalyzed(idx); unresolved++
        }
        while (!done) {
            var l: Int
            var idx: Int
            do { l = trail[--tpos]; idx = idxOf(l) } while (!aAnalyzed[idx])
            if (unresolved == 1) { unit = not(l); units.add(unit) }
            if (aBinary[idx]) {
                val other = aReason[idx]
                if (other == notFailed) { done = true; break }
                val oidx = idxOf(other)
                if (!aAnalyzed[oidx]) { pushAnalyzed(oidx); unresolved++ }
            } else {
                val ref = aReason[idx]
                for (other in lits(ref)) {
                    if (other == l) continue
                    if (other == unit) continue
                    if (other == notFailed) { done = true; break }
                    val oidx = idxOf(other)
                    if (aLevel[oidx] == 0) continue
                    if (aAnalyzed[oidx]) continue
                    pushAnalyzed(oidx); unresolved++
                }
                if (done) break
            }
            unresolved--
        }
        units.add(notFailed)
        backtrackWithoutUpdatingPhases(0)
        for (t in 0 until units.size) learnedUnit(units[t])
        iterating = true
    }

    // update tier limits on the glue interval (analyze.c update_tier_limits)
    private fun updateTierLimits() {
        retiered++
        computeAndSetTierLimits()
        if (limGlueInterval < (1L shl 16)) limGlueInterval *= 2
        limGlueConflicts = conflicts + limGlueInterval
    }

    // =========================================================================
    // init limits
    // =========================================================================
    private fun initLimits() {
        limReduceConflicts = conflicts + gReduceInt
        if (!stable) updateFocusedRestartLimit()
        initModeLimit()
        for (s in 0 until 2) {
            if (tier1arr[s] == 0) {
                tier1arr[s] = OPT_tier1
                tier2arr[s] = OPT_tier2
                if (tier2arr[s] <= tier1arr[s]) tier2arr[s] = tier1arr[s]
            }
        }
        if (limGlueInterval == 0L) limGlueInterval = 2
        limGlueConflicts = conflicts + limGlueInterval
    }

    // =========================================================================
    // top-level search (search.c)
    // =========================================================================
    private fun searchLoop(): Int {
        if (inconsistent) { tr("RESULT UNSAT"); return 20 }
        stable = (OPT_stable == 2)   // default 1 -> focused first
        initAverages()
        if (stable) { reluctant.enable(OPT_reluctantint, OPT_reluctantlim); updateScores() }
        initLimits()

        var res = 0
        while (res == 0) {
            val conflict = searchPropagate()
            if (conflict != INVALID_REF) {
                res = analyze(conflict)
                // update tier limits on interval (as in analyze.c after deduce)
                if (conflicts > limGlueConflicts) updateTierLimits()
            } else if (iterating) { iterating = false }
            else if (unassigned == 0) res = 10
            else if (reducing()) res = reduce()
            else if (switchingSearchMode()) switchSearchMode()
            else if (restarting()) restart()
            else decide()
        }

        if (res == 10) { captureModel(); tr("RESULT SAT") }
        else tr("RESULT UNSAT")
        return res
    }
    private fun captureModel() {
        model = ByteArray(vars)
        for (i in 0 until vars) model[i] = values[lit(i)]
    }

    // =========================================================================
    // Public SatSolver API
    // =========================================================================
    override fun addClause(literals: IntArray) {
        if (inconsistent) return
        // convert DIMACS -> internal literals, drop root-false / satisfied, dedup + taut
        val ps = IntArrayList()
        var taut = false
        for (dl in literals) {
            val idx = (if (dl < 0) -dl else dl) - 1
            ensureVars(idx + 1)
            val ilit = lit(idx) or (if (dl < 0) 1 else 0)
            val v = values[ilit].toInt()
            if (v > 0 && aLevel[idx] == 0) { taut = true; break }
            if (v < 0 && aLevel[idx] == 0) continue
            var dup = false
            var i = 0
            while (i < ps.size) {
                val qq = ps[i]
                if (qq == ilit) { dup = true; break }
                if (qq == not(ilit)) { taut = true; break }
                i++
            }
            if (taut) break
            if (!dup) ps.add(ilit)
        }
        if (taut) return
        newOriginalClause(ps.toArray())
        // propagate parse-time units immediately (kissat propagates at root)
        if (!inconsistent && unflushed != 0) {
            val cf = searchPropagate()
            if (cf != INVALID_REF) inconsistent = true
        }
    }

    override fun solve(): SatResult {
        val r = searchLoop()
        return if (r == 10) SatResult.SAT else SatResult.UNSAT
    }

    override fun valueOf(v: Int): Boolean {
        val idx = v - 1
        if (idx < 0 || idx >= model.size) return false
        return model[idx].toInt() > 0
    }
}

/** Minimal growable int list (avoids boxing; mirrors the flat-array style of the C port). */
class IntArrayList {
    private var a = IntArray(16)
    var size = 0
        private set
    fun add(x: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    operator fun get(i: Int): Int = a[i]
    operator fun set(i: Int, v: Int) { a[i] = v }
    fun removeLast() { size-- }
    fun clear() { size = 0 }
    fun shrinkTo(n: Int) { size = n }
    fun toArray(): IntArray = a.copyOf(size)
}

/** Minimal growable boolean list, parallel to [IntArrayList] for the frame 'promote' flag. */
class BooleanList {
    private var a = BooleanArray(16)
    var size = 0
        private set
    fun add(x: Boolean) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = x }
    operator fun get(i: Int): Boolean = a[i]
    operator fun set(i: Int, v: Boolean) { a[i] = v }
    fun clear() { size = 0 }
    fun shrinkTo(n: Int) { size = n }
}
