package org.bytefred.ksat

/** Result of a solve. */
enum class SatResult { SAT, UNSAT }

/**
 * Common interface implemented by every Kotlin SAT solver port in this repo.
 *
 * A CNF is given as a list of clauses, each clause a list of non-zero signed
 * DIMACS literals (variable v is 1..nVars, `-v` is its negation). Solvers report
 * SAT/UNSAT and, on SAT, expose a model.
 */
interface SatSolver {
    /** Number of variables the solver was initialized for. */
    val numVars: Int

    /** Add a clause (list of signed literals). Must be called before [solve]. */
    fun addClause(literals: IntArray)

    /** Solve the current formula (no assumptions). */
    fun solve(): SatResult = solve(IntArray(0))

    /**
     * Solve the loaded formula UNDER the given [assumptions] (signed DIMACS literals),
     * the standard incremental interface (as in PySAT / IPASIR). Each assumption is
     * forced before free search; on return the solver backtracks to level 0 so the SAME
     * instance can be solved again under different assumptions on the exact same clause
     * set — no clause reload. On SAT, [valueOf] reflects the model of THIS solve; on
     * UNSAT the assumptions were jointly inconsistent with the formula.
     *
     * A solver that has not (yet) ported its assumptions path throws for non-empty
     * [assumptions]; the empty case is a plain solve.
     */
    fun solve(assumptions: IntArray): SatResult {
        require(assumptions.isEmpty()) {
            "this solver does not support assumptions yet"
        }
        return solve()
    }

    /** After a SAT result: true iff variable `v` (1..numVars) is assigned true. */
    fun valueOf(v: Int): Boolean
}

/**
 * Optional hook a solver implements to emit a step-by-step decision trace, used to
 * shadow the Kotlin port against its C reference. When [sink] is non-null the solver
 * emits one line per internal event in the shared trace format
 * (DECIDE / ASSIGN / CONFLICT / RESTART / REDUCE / RESULT).
 */
interface Traceable {
    fun setTraceSink(sink: ((String) -> Unit)?)
}
