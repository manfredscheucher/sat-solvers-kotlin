package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stress the REUSE path harder than AssumptionsTest: on the same solver instance, run
 * assumptions that make the formula UNSAT-under-assumptions (a genuine conflict driven by
 * the assumption), THEN run assumptions that should be SAT again. If a solve poisons the
 * permanent clause DB (e.g. kissat learning not(assumption) as a level-0 unit via the
 * level-1-conflict "failed literal" path), the later solves flip verdict.
 *
 * Reviewer-authored regression to probe the pseudo-frame / analyzeFailedLiteral concern.
 */
class AssumptionsReuseStressTest {

    private val solvers = listOf(Solver.MINISAT, Solver.CADICAL, Solver.KISSAT)

    // CNF over 4 vars:
    //   (x1 OR x2) AND (-x1 OR x3) AND (-x1 OR -x3)
    // Under assumption x1=true: (-x1 OR x3)=>x3, (-x1 OR -x3)=>-x3  => conflict at the
    // assumption's decision level. So solve([1]) is UNSAT. But the formula itself is SAT
    // (x1=false). A correct reusable solver must still say SAT for e.g. solve([-1]).
    private val clauses = listOf(
        intArrayOf(1, 2),
        intArrayOf(-1, 3),
        intArrayOf(-1, -3),
    )

    private fun build(s: Solver): Ksat {
        val k = Ksat(s, numVars = 4)
        for (c in clauses) k.addClause(c)
        return k
    }

    private fun reference(s: Solver, assumption: Int): SatResult {
        val k = Ksat(s, numVars = 4)
        for (c in clauses) k.addClause(c)
        k.addClause(intArrayOf(assumption))
        return k.solve()
    }

    @Test
    fun unsatUnderAssumptionDoesNotPoisonLaterSolves() {
        for (s in solvers) {
            val reused = build(s)
            // sequence deliberately: an UNSAT-under-assumption first, then SAT ones
            for (a in intArrayOf(1, -1, 3, -1, 1, -3, 2, -2)) {
                val got = reused.solve(intArrayOf(a))
                val want = reference(s, a)
                assertEquals(want, got, "solver $s: reused solve under $a must match a fresh solver")
            }
        }
    }

    @Test
    fun pseudoFrameFirstAssumptionAlreadyTrue() {
        // Make x4 a permanent unit (level-0 true), then assume it. The first assumption is
        // already satisfied -> pseudo frame path. Follow with more assumptions to exercise
        // restart/reuse-trail over a pseudo frame.
        for (s in solvers) {
            val k = Ksat(s, numVars = 4)
            for (c in clauses) k.addClause(c)
            k.addClause(intArrayOf(4)) // x4 forced true at level 0
            for (a in intArrayOf(4, -1, 4, 2)) {
                val got = k.solve(intArrayOf(4, a))
                // reference: clauses + unit x4 + unit a
                val ref = Ksat(s, numVars = 4)
                for (c in clauses) ref.addClause(c)
                ref.addClause(intArrayOf(4))
                ref.addClause(intArrayOf(a))
                assertEquals(ref.solve(), got, "solver $s: pseudo-frame reuse under [4,$a]")
            }
        }
    }
}
