package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult
import org.bytefred.ksat.SatSolver
import org.bytefred.ksat.cadical.CaDiCaL
import org.bytefred.ksat.kissat.Kissat
import org.bytefred.ksat.minisat.MiniSat

/** The ported solvers you can pick between. */
enum class Solver { MINISAT, CADICAL, KISSAT }

/**
 * One uniform entry point over the ported SAT solvers, in the spirit of PySAT: the
 * CNF you feed in and the SAT/UNSAT + model you get back are the same regardless of
 * which [Solver] runs underneath, so switching solver is a one-word change.
 *
 * ```
 * val s = Ksat(Solver.CADICAL, numVars = n)
 * for (c in clauses) s.addClause(c)
 * if (s.solve(assumptions = intArrayOf(-mine)) == SatResult.SAT) {
 *     val isMine = s.valueOf(v)   // model of that solve
 * }
 * ```
 *
 * [Ksat] is itself a [SatSolver], so game/library code can hold the interface type
 * and stay solver-agnostic; the concrete choice is made once at construction.
 *
 * `numVars` is a capacity hint (as in the underlying ports): variables are created
 * lazily as clauses reference them.
 */
class Ksat(
    val solver: Solver,
    numVars: Int,
) : SatSolver {

    private val delegate: SatSolver = when (solver) {
        Solver.MINISAT -> MiniSat(numVars)
        Solver.CADICAL -> CaDiCaL(numVars)
        Solver.KISSAT -> Kissat(numVars)
    }

    override val numVars: Int get() = delegate.numVars

    override fun addClause(literals: IntArray) = delegate.addClause(literals)

    override fun solve(): SatResult = delegate.solve()

    /** Solve under [assumptions]; see [SatSolver.solve]. Delegated to the chosen port. */
    override fun solve(assumptions: IntArray): SatResult = delegate.solve(assumptions)

    override fun valueOf(v: Int): Boolean = delegate.valueOf(v)
}
