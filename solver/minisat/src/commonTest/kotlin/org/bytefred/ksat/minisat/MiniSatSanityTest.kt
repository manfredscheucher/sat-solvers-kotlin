package org.bytefred.ksat.minisat

import org.bytefred.ksat.DimacsCnf
import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Target-independent sanity + L3 tests for the Kotlin [MiniSat] port. These run on all
 * targets (no file access, no C reference needed): they pin the SAT/UNSAT verdict for a
 * set of instances with known answers and, on SAT, check the returned model satisfies
 * the CNF. The full step-by-step trace shadow against the C MiniSat lives in the JVM-only
 * ShadowTraceFilesTest, which reads the golden traces produced by minisat_trace.cc.
 */
class MiniSatSanityTest {

    private fun solve(cnfText: String): Pair<SatResult, DimacsCnf> {
        val cnf = DimacsCnf.parse(cnfText)
        val solver = MiniSat(cnf.numVars)
        for (clause in cnf.clauses) solver.addClause(clause)
        val r = solver.solve()
        if (r == SatResult.SAT) {
            assertTrue(cnf.isSatisfiedBy { v -> solver.valueOf(v) }, "model does not satisfy the CNF")
        }
        return r to cnf
    }

    private fun assertVerdict(name: String, cnf: String, expected: SatResult) {
        val (r, _) = solve(cnf)
        assertEquals(expected, r, "[$name] wrong verdict")
    }

    @Test fun satSmall() = assertVerdict(
        "sat_small",
        """
        p cnf 4 5
        1 2 0
        -2 3 0
        -3 4 0
        1 -4 0
        2 4 0
        """.trimIndent(),
        SatResult.SAT,
    )

    @Test fun unsatSmall() = assertVerdict(
        "unsat_small",
        """
        p cnf 3 6
        1 2 0
        -1 2 0
        1 -2 0
        -1 -2 0
        3 0
        -3 0
        """.trimIndent(),
        SatResult.UNSAT,
    )

    @Test fun conflictThenSat() = assertVerdict(
        "conflict_sat",
        """
        p cnf 6 8
        1 2 0
        -1 3 0
        -2 3 0
        -3 4 0
        4 5 0
        -4 6 0
        -5 -6 0
        2 6 0
        """.trimIndent(),
        SatResult.SAT,
    )

    @Test fun php32Unsat() = assertVerdict(
        "php_3_2",
        """
        p cnf 6 9
        1 2 0
        3 4 0
        5 6 0
        -1 -3 0
        -1 -5 0
        -3 -5 0
        -2 -4 0
        -2 -6 0
        -4 -6 0
        """.trimIndent(),
        SatResult.UNSAT,
    )

    @Test fun php43Unsat() {
        // PHP(4,3): 12 vars, guaranteed UNSAT. Exercises conflict analysis heavily.
        val holes = 3; val pigeons = 4
        fun v(p: Int, h: Int) = (p - 1) * holes + h
        val clauses = StringBuilder()
        var m = 0
        for (p in 1..pigeons) { clauses.append((1..holes).joinToString(" ") { v(p, it).toString() }).append(" 0\n"); m++ }
        for (h in 1..holes) for (p1 in 1..pigeons) for (p2 in p1 + 1..pigeons) {
            clauses.append("-${v(p1, h)} -${v(p2, h)} 0\n"); m++
        }
        assertVerdict("php_4_3", "p cnf ${pigeons * holes} $m\n$clauses", SatResult.UNSAT)
    }

    @Test fun unitPropagationChain() = assertVerdict(
        "unit_chain",
        """
        p cnf 4 5
        1 0
        -1 2 0
        -2 3 0
        -3 4 0
        -4 0
        """.trimIndent(),
        SatResult.UNSAT,
    )
}
