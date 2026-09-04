package org.bytefred.ksat.microsat

import org.bytefred.ksat.SatResult
import org.bytefred.ksat.SatSolver
import org.bytefred.ksat.Traceable

/**
 * Faithful Kotlin port of Marijn Heule's MicroSAT (MIT, (c) 2014-2018 Marijn Heule).
 * See ../shadow/microsat-c/ for the C original + instrumented reference.
 *
 * This is a deliberate 1:1 translation so the port can be shadowed step-by-step
 * against the C version: same VMTF decision order, same watched-literal propagation,
 * same 1-UIP conflict analysis, same integer moving-average restart schedule. The C
 * code stores everything in one `int* DB` with pointer arithmetic; here that is an
 * [IntArray] with integer indices. The C code uses arrays indexed by signed literals
 * (`false[-lit]`, `first[-lit]`); here those live in arrays of size `2n+1` accessed
 * via a `+ n` offset (see [fal] / [fst]).
 *
 * Not idiomatic Kotlin on purpose — readability defers to matching the C exactly.
 *
 * @param numVarsHint number of variables (C: `n` in `initCDCL`).
 * @param maxLemmasInit initial `maxLemmas` (C: `S->maxLemmas = 2000`). This is a
 *   TEST-ONLY knob: MicroSAT's REDUCE path only fires once the learnt-clause count
 *   exceeds `maxLemmas`, and with the upstream default of 2000 no *fast* instance
 *   reaches it. Lowering it (e.g. to 3) forces `reduceDB` on a small UNSAT instance
 *   so the dual-location watch-pointer compaction gets trace-covered. The default
 *   equals the upstream value, so normal behaviour is byte-for-byte unchanged. The
 *   C reference mirrors this via the `LSMAXLEMMAS` env var (see microsat_trace.c).
 */
class MicroSat(
    numVarsHint: Int,
    private val maxLemmasInit: Int = 2000,
    // TEST-ONLY knob (mirrors the C reference's LSRESTARTFACTOR): restart fires when
    // `fast > slow/100 * restartFactorInit`. Upstream default 125; lowering it makes restarts
    // (and thus REDUCE) fire on small instances so those paths get trace-covered.
    private val restartFactorInit: Int = 125,
) : SatSolver, Traceable {

    private companion object {
        const val END = -9
        const val UNSAT = 0
        const val SAT = 1
        const val MARK = 2
        const val IMPLIED = 6
        const val MEM_MAX = 1 shl 24  // plenty for the small instances this game produces
    }

    override var numVars: Int = if (numVarsHint < 1) 1 else numVarsHint
        private set

    // --- The single flat database (C: int* DB) ---
    private val db = IntArray(MEM_MAX)
    private var memUsed = 0
    private var memFixed = 0
    private var maxLemmas = maxLemmasInit
    private var nLemmas = 0
    private var nConflicts = 0
    private var fast = 1 shl 24
    private var slow = 1 shl 24
    private var res = 0

    // Per-variable arrays (index 0..numVars). model = phase saving, reason = clause offset.
    private lateinit var model: IntArray
    private lateinit var next: IntArray
    private lateinit var prev: IntArray
    private lateinit var buffer: IntArray
    private lateinit var reason: IntArray

    // false / first are indexed by signed literal in C (-n..n) via pointer offset;
    // here they are size 2n+1 and every access adds `off` (= numVars).
    private lateinit var falseArr: IntArray
    private lateinit var firstArr: IntArray
    private var off = 0
    private inline fun fal(lit: Int): Int = falseArr[lit + off]
    private inline fun setFal(lit: Int, v: Int) { falseArr[lit + off] = v }
    private inline fun fst(lit: Int): Int = firstArr[lit + off]
    private inline fun setFst(lit: Int, v: Int) { firstArr[lit + off] = v }

    // The assignment stack (C: falseStack) plus the three moving pointers into it,
    // kept as integer indices `forced`/`processed`/`assigned` into `stack`.
    private lateinit var stack: IntArray
    private var forced = 0
    private var processed = 0
    private var assigned = 0
    private var head = 0

    private var trace: ((String) -> Unit)? = null
    override fun setTraceSink(sink: ((String) -> Unit)?) { trace = sink }
    private inline fun tr(line: String) { trace?.invoke(line) }

    init { initCDCL(numVars) }

    private fun getMemory(size: Int): Int {
        if (memUsed + size > MEM_MAX) throw IllegalStateException("out of memory")
        val store = memUsed
        memUsed += size
        return store
    }

    private fun initCDCL(n: Int) {
        numVars = if (n < 1) 1 else n
        off = numVars
        memUsed = 0
        nLemmas = 0
        nConflicts = 0
        maxLemmas = maxLemmasInit
        fast = 1 shl 24
        slow = 1 shl 24

        model = IntArray(numVars + 1)
        next = IntArray(numVars + 1)
        prev = IntArray(numVars + 1)
        buffer = IntArray(numVars + 1)
        reason = IntArray(numVars + 1)
        stack = IntArray(numVars + 1)
        forced = 0; processed = 0; assigned = 0
        falseArr = IntArray(2 * numVars + 1)
        firstArr = IntArray(2 * numVars + 1)

        db[memUsed++] = 0  // ensure a 0 before clauses are loaded

        for (i in 1..numVars) {
            prev[i] = i - 1; next[i - 1] = i
            model[i] = 0; setFal(-i, 0); setFal(i, 0)
            setFst(i, END); setFst(-i, END)
        }
        head = numVars
    }

    private fun unassign(lit: Int) { setFal(lit, 0) }

    private fun restart() {
        tr("RESTART")
        while (assigned > forced) unassign(stack[--assigned])
        processed = forced
    }

    /** C: assign(S, reason, forced). `reasonOff` is an index into db pointing at the reason clause's first literal. */
    private fun assign(reasonOff: Int, forcedFlag: Boolean) {
        val lit = db[reasonOff]
        setFal(-lit, if (forcedFlag) IMPLIED else 1)
        stack[assigned++] = -lit
        reason[abs(lit)] = 1 + reasonOff
        model[abs(lit)] = if (lit > 0) 1 else 0
        tr("ASSIGN $lit reason=${if (forcedFlag) 'F' else 'C'}")
    }

    private fun addWatch(lit: Int, mem: Int) {
        db[mem] = fst(lit); setFst(lit, mem)
    }

    /** C: addClause. `inBuf`/`inOff` is the source array; returns the db offset of the clause's first literal. */
    private fun addClause(inBuf: IntArray, inOff: Int, size: Int, irr: Boolean): Int {
        val used = memUsed
        val clause = getMemory(size + 3) + 2
        if (size > 1) {
            addWatch(inBuf[inOff], used)
            addWatch(inBuf[inOff + 1], used + 1)
        }
        for (i in 0 until size) db[clause + i] = inBuf[inOff + i]
        db[clause + size] = 0
        if (irr) memFixed = memUsed else nLemmas++
        return clause
    }

    private fun reduceDB(k: Int) {
        tr("REDUCE")
        while (nLemmas > maxLemmas) maxLemmas += 300
        nLemmas = 0

        var i = -numVars
        while (i <= numVars) {
            if (i == 0) { i++; continue }
            // `watch` is a location: either the offset-array slot for first[i], or a db index.
            // We model C's `int* watch` with a small indirection: negative sentinel = firstArr slot.
            var watchIsFirst = true
            var watchIdx = i  // meaning firstArr[i + off]
            while (true) {
                val cur = if (watchIsFirst) fst(i) else db[watchIdx]
                if (cur == END) break
                if (cur < memFixed) {
                    // advance: watch = db + cur  -> next watch is db[cur]
                    watchIsFirst = false
                    watchIdx = cur
                } else {
                    // *watch = db[*watch]
                    if (watchIsFirst) setFst(i, db[cur]) else db[watchIdx] = db[cur]
                }
            }
            i++
        }

        val oldUsed = memUsed
        memUsed = memFixed
        // C: for (i = mem_fixed+2; i < old_used; i += 3) { head=i; while(DB[i]) i++; ...; }
        // The inner while advances `i` to the clause terminator; the for-step then adds 3.
        var j = memFixed + 2
        while (j < oldUsed) {
            var count = 0
            val headIdx = j
            while (db[j] != 0) {
                val lit = db[j++]
                if ((lit > 0) == (model[abs(lit)] == 1)) count++
            }
            if (count < k) addClause(db, headIdx, j - headIdx, false)
            j += 3
        }
    }

    private fun bump(lit: Int) {
        if (fal(lit) != IMPLIED) {
            setFal(lit, MARK)
            val v = abs(lit)
            if (v != head) {
                prev[next[v]] = prev[v]
                next[prev[v]] = next[v]
                next[head] = v
                prev[v] = head; head = v
            }
        }
    }

    private fun implied(lit: Int): Boolean {
        if (fal(lit) > MARK) return (fal(lit) and MARK) != 0
        if (reason[abs(lit)] == 0) return false
        var p = reason[abs(lit)] - 1  // db index; C: p = DB + reason - 1, then ++p
        while (true) {
            p++
            val q = db[p]
            if (q == 0) break
            if ((fal(q) xor MARK) != 0 && !implied(q)) {
                setFal(lit, IMPLIED - 1); return false
            }
        }
        setFal(lit, IMPLIED); return true
    }

    /** C: analyze(S, clause) where `clause` is a db index at the clause's first literal. Returns db offset of learned clause. */
    private fun analyze(clauseStart: Int): Int {
        tr("CONFLICT")
        res++; nConflicts++
        var c = clauseStart
        while (db[c] != 0) { bump(db[c]); c++ }

        while (reason[abs(stack[--assigned])] != 0) {
            if (fal(stack[assigned]) == MARK) {
                var check = assigned
                var uip = false
                while (fal(stack[--check]) != MARK) {
                    if (reason[abs(stack[check])] == 0) { uip = true; break }
                }
                if (uip) break
                var r = reason[abs(stack[assigned])]  // db index
                while (db[r] != 0) { bump(db[r]); r++ }
            }
            unassign(stack[assigned])
        }

        // build:
        var size = 0; var lbd = 0; var flag = 0
        processed = assigned
        var p = assigned
        while (p >= forced) {
            if (fal(stack[p]) == MARK && !implied(stack[p])) {
                buffer[size++] = stack[p]; flag = 1
            }
            if (reason[abs(stack[p])] == 0) {
                lbd += flag; flag = 0
                if (size == 1) processed = p
            }
            setFal(stack[p], 1)
            p--
        }

        fast -= fast shr 5; fast += lbd shl 15
        slow -= slow shr 15; slow += lbd shl 5

        while (assigned > processed) unassign(stack[assigned--])
        unassign(stack[assigned])
        buffer[size] = 0
        return addClause(buffer, 0, size, false)
    }

    private fun propagate(): Int {
        var forcedFlag = reason[abs(stack[processed])] != 0
        while (processed < assigned) {
            val lit = stack[processed++]
            // C: int* watch = &first[lit]; we track whether watch points at the firstArr slot or a db index.
            var watchIsFirst = true
            var watchIdx = lit
            while (true) {
                val cur = if (watchIsFirst) fst(lit) else db[watchIdx]
                if (cur == END) break
                var unit = true
                var clause = cur + 1  // C: DB + *watch + 1
                if (db[clause - 2] == 0) clause++
                if (db[clause] == lit) db[clause] = db[clause + 1]
                var i = 2
                while (unit && db[clause + i] != 0) {
                    if (fal(db[clause + i]) == 0) {
                        db[clause + 1] = db[clause + i]; db[clause + i] = lit
                        val store = cur
                        unit = false
                        if (watchIsFirst) setFst(lit, db[cur]) else db[watchIdx] = db[cur]
                        addWatch(db[clause + 1], store)
                    }
                    i++
                }
                if (unit) {
                    db[clause + 1] = lit
                    // watch = DB + *watch -> advance to next
                    watchIsFirst = false
                    watchIdx = cur
                    if (fal(-db[clause]) != 0) continue
                    if (fal(db[clause]) == 0) {
                        assign(clause, forcedFlag)
                    } else {
                        if (forcedFlag) return UNSAT
                        val lemma = analyze(clause)
                        if (db[lemma + 1] == 0) forcedFlag = true
                        assign(lemma, forcedFlag)
                        break
                    }
                }
            }
        }
        if (forcedFlag) forced = processed
        return SAT
    }

    private fun solveInternal(): Int {
        var decision = head
        res = 0
        while (true) {
            val oldNLemmas = nLemmas
            if (propagate() == UNSAT) { tr("RESULT UNSAT"); return UNSAT }

            if (nLemmas > oldNLemmas) {
                decision = head
                if (fast > (slow / 100) * restartFactorInit) {
                    res = 0; fast = (slow / 100) * restartFactorInit; restart()
                    if (nLemmas > maxLemmas) reduceDB(6)
                }
            }

            while (fal(decision) != 0 || fal(-decision) != 0) decision = prev[decision]
            if (decision == 0) { tr("RESULT SAT"); return SAT }
            decision = if (model[decision] == 1) decision else -decision
            tr("DECIDE $decision")
            setFal(-decision, 1)
            stack[assigned++] = -decision
            decision = abs(decision); reason[decision] = 0
        }
    }

    // --- Public SatSolver API ---

    private var earlyUnsat = false

    override fun addClause(literals: IntArray) {
        if (earlyUnsat) return
        val size = literals.size
        for (i in 0 until size) buffer[i] = literals[i]
        val clause = addClause(buffer, 0, size, true)
        if (size == 0 || (size == 1 && fal(db[clause]) != 0)) { earlyUnsat = true; return }
        if (size == 1 && fal(-db[clause]) == 0) assign(clause, true)
    }

    override fun solve(): SatResult {
        if (earlyUnsat) { tr("RESULT UNSAT"); return SatResult.UNSAT }
        return if (solveInternal() == SAT) SatResult.SAT else SatResult.UNSAT
    }

    override fun valueOf(v: Int): Boolean = model[v] == 1

    private fun abs(x: Int): Int = if (x < 0) -x else x
}
