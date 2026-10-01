package org.bytefred.ksat.facade

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the shared [enumerateModels] all-SAT helper (the same one the ksat-extra demo uses).
 * XOR(x1,x2) = (x1 OR x2) AND (NOT x1 OR NOT x2) has exactly two models {x1,-x2} and {-x1,x2},
 * then UNSAT.
 */
class EnumerateModelsTest {

    private val solvers = listOf(Solver.MINISAT, Solver.CADICAL, Solver.KISSAT)

    // XOR(x1, x2)
    private val xor = listOf(intArrayOf(1, 2), intArrayOf(-1, -2))

    @Test
    fun enumeratesExactlyTwoModelsThenUnsat() {
        for (s in solvers) {
            val models = enumerateModels(s, numVars = 2, cnf = xor, limit = 8)
            assertEquals(2, models.size, "solver $s: XOR should have exactly 2 models")
            assertEquals(
                setOf(listOf(true, false), listOf(false, true)),
                models.toSet(),
                "solver $s: models must be {x1,-x2} and {-x1,x2}",
            )
        }
    }
}
