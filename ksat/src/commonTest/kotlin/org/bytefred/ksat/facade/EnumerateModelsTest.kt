package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * All-SAT enumeration by blocking clauses. NOTE: these ports do not accept new permanent
 * clauses added AFTER a solve() (a mid-search addClause does not take), so enumeration uses
 * a FRESH solver each round, replaying the base CNF plus every model-blocking clause found so
 * far. solve -> read model -> block it -> new solver with that block added -> repeat, until
 * UNSAT. This is the pattern the ksat-extra demo uses. XOR(x1,x2) has exactly two models
 * {x1,-x2} and {-x1,x2}, then UNSAT.
 */
class EnumerateModelsTest {

    private val solvers = listOf(Solver.MINISAT, Solver.CADICAL, Solver.KISSAT)

    // XOR(x1, x2) = (x1 OR x2) AND (-x1 OR -x2)
    private val xor = listOf(intArrayOf(1, 2), intArrayOf(-1, -2))

    private fun enumerate(s: Solver): List<List<Boolean>> {
        val nVars = 2
        val blocks = mutableListOf<IntArray>()
        val models = mutableListOf<List<Boolean>>()
        while (true) {
            val k = Ksat(s, numVars = nVars)
            for (c in xor) k.addClause(c)
            for (b in blocks) k.addClause(b)
            if (k.solve() != SatResult.SAT) break
            val m = (1..nVars).map { k.valueOf(it) }
            models += m
            // block this exact assignment: OR of the negated literals
            blocks += IntArray(nVars) { i -> if (m[i]) -(i + 1) else (i + 1) }
            if (models.size > 8) break // safety net
        }
        return models
    }

    @Test
    fun enumeratesExactlyTwoModelsThenUnsat() {
        for (s in solvers) {
            val models = enumerate(s)
            assertEquals(2, models.size, "solver $s: XOR should have exactly 2 models")
            assertEquals(
                setOf(listOf(true, false), listOf(false, true)),
                models.toSet(),
                "solver $s: models must be {x1,-x2} and {-x1,x2}",
            )
        }
    }
}
