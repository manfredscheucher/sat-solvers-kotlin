package org.bytefred.ksat.facade

import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The facade contract: the same CNF must give the same verdict (and a satisfying
 * model on SAT) whichever [Solver] is chosen. Assumptions are exercised in Phase 2
 * once every port implements them; here we pin the uniform select-and-solve path.
 */
class KsatFacadeTest {

    private val allSolvers = Solver.entries

    @Test
    fun satInstanceAgreesAcrossSolvers() {
        // (x1 OR x2) AND (NOT x1) -> SAT, forces x2 = true.
        for (s in allSolvers) {
            val k = Ksat(s, numVars = 2)
            k.addClause(intArrayOf(1, 2))
            k.addClause(intArrayOf(-1))
            assertEquals(SatResult.SAT, k.solve(), "solver $s should report SAT")
            assertTrue(k.valueOf(2), "solver $s: x2 must be true")
        }
    }

    @Test
    fun unsatInstanceAgreesAcrossSolvers() {
        // (x1) AND (NOT x1) -> UNSAT.
        for (s in allSolvers) {
            val k = Ksat(s, numVars = 1)
            k.addClause(intArrayOf(1))
            k.addClause(intArrayOf(-1))
            assertEquals(SatResult.UNSAT, k.solve(), "solver $s should report UNSAT")
        }
    }

    @Test
    fun facadeReportsChosenSolver() {
        assertEquals(Solver.KISSAT, Ksat(Solver.KISSAT, numVars = 1).solver)
    }
}
