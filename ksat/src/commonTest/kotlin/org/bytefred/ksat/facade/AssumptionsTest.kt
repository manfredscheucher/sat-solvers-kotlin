package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Solving under an assumption must give the same verdict (and, on SAT, a model that
 * respects the assumption) as adding that assumption as a unit clause to a fresh solver.
 * That is the whole point of the incremental interface: same answer, no clause reload.
 * Checked for every solver, plus a reuse round (several assumptions on one instance),
 * which is how the caller actually uses it.
 */
class AssumptionsTest {

    // Solvers whose assumptions path is ported. kissat is added here once done (Task #4);
    // until then it throws on non-empty assumptions by the interface default, and listing
    // it here would be a visible failure rather than a silent skip.
    private val solversWithAssumptions = listOf(Solver.MINISAT, Solver.CADICAL, Solver.KISSAT)

    // A small satisfiable CNF over 3 vars with more than one model:
    //   (x1 OR x2 OR x3) AND (NOT x1 OR x2)
    private val clauses = listOf(
        intArrayOf(1, 2, 3),
        intArrayOf(-1, 2),
    )

    private fun fresh(s: Solver): Ksat {
        val k = Ksat(s, numVars = 3)
        for (c in clauses) k.addClause(c)
        return k
    }

    /** Reference: same CNF plus the assumption as a unit clause, on a brand-new solver. */
    private fun referenceVerdict(s: Solver, assumption: Int): SatResult {
        val k = Ksat(s, numVars = 3)
        for (c in clauses) k.addClause(c)
        k.addClause(intArrayOf(assumption))
        return k.solve()
    }

    @Test
    fun assumptionMatchesUnitClauseVerdict() {
        for (s in solversWithAssumptions) {
            for (assumption in intArrayOf(1, -1, 2, -2, 3, -3)) {
                val underAssumption = fresh(s).solve(intArrayOf(assumption))
                val reference = referenceVerdict(s, assumption)
                assertEquals(
                    reference, underAssumption,
                    "solver $s, assumption $assumption: solve-under-assumption must match the unit-clause verdict",
                )
            }
        }
    }

    @Test
    fun modelRespectsAssumptionOnSat() {
        for (s in solversWithAssumptions) {
            val k = fresh(s)
            // force x1 = true; (NOT x1 OR x2) then forces x2 = true
            assertEquals(SatResult.SAT, k.solve(intArrayOf(1)), "solver $s should be SAT under x1")
            assertTrue(k.valueOf(1), "solver $s: x1 must be true (assumed)")
            assertTrue(k.valueOf(2), "solver $s: x2 must be true (implied)")
        }
    }

    @Test
    fun sameInstanceReusableAcrossAssumptions() {
        // The caller keeps ONE solver and queries many assumptions on it. Each query must
        // give the same verdict as a fresh solver would — i.e. no state leaks between solves.
        for (s in solversWithAssumptions) {
            val reused = fresh(s)
            for (assumption in intArrayOf(1, -1, 2, -2, 3, -3, 1, 3)) {
                val got = reused.solve(intArrayOf(assumption))
                val want = referenceVerdict(s, assumption)
                assertEquals(want, got, "solver $s: reused solve under $assumption must match a fresh one")
            }
        }
    }
}
