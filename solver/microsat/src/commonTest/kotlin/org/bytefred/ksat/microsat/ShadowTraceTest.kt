package org.bytefred.ksat.microsat

import org.bytefred.ksat.DimacsCnf
import org.bytefred.ksat.SatResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shadow test: run the Kotlin [MicroSat] with a trace sink and compare its trace,
 * line-for-line, against the golden trace produced by the instrumented C reference
 * (shadow/microsat-c/microsat_trace.c, run with LSTRACE=1). Also checks the SAT/UNSAT
 * verdict and, on SAT, that the returned model actually satisfies the CNF.
 *
 * The CNF text and golden C traces are embedded here so commonTest needs no file access.
 * Regenerate golden traces with: shadow/build_and_trace.sh (or cc -O2 + LSTRACE=1).
 */
class ShadowTraceTest {

    // --- Embedded CNFs (verbatim from shadow/cnf/*.cnf) ---

    private val satSmall = """
        c Small SAT instance exercising decisions + unit propagation. 4 vars.
        p cnf 4 5
        1 2 0
        -2 3 0
        -3 4 0
        1 -4 0
        2 4 0
    """.trimIndent()

    private val unsatSmall = """
        c Small UNSAT instance: (a) and (not a) forced via clauses -> UNSAT.
        c Also exercises conflict analysis. 3 vars.
        p cnf 3 6
        1 2 0
        -1 2 0
        1 -2 0
        -1 -2 0
        3 0
        -3 0
    """.trimIndent()

    private val conflictSmall = """
        c Forces conflict analysis then finds a model. 5 vars.
        p cnf 5 7
        1 2 0
        -1 3 0
        -2 3 0
        -3 4 0
        -3 5 0
        -4 -5 0
        1 -2 0
    """.trimIndent()

    private val conflictSat = """
        c Conflict during search, then a model is found. 6 vars.
        p cnf 6 8
        1 2 0
        -1 3 0
        -2 3 0
        -3 4 0
        4 5 0
        -4 6 0
        -5 -6 0
        2 6 0
    """.trimIndent()

    private val php32 = """
        c Pigeonhole 3 pigeons 2 holes -> UNSAT, exercises many conflicts.
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
    """.trimIndent()

    // --- Golden C traces (LSTRACE=1 microsat_trace <cnf>, comment/status lines stripped) ---

    private val satSmallTrace = listOf(
        "DECIDE -4",
        "ASSIGN 2 reason=C",
        "ASSIGN -3 reason=C",
        "CONFLICT",
        "ASSIGN 4 reason=F",
        "ASSIGN 1 reason=F",
        "DECIDE 2",
        "ASSIGN 3 reason=C",
        "RESULT SAT",
    )

    private val unsatSmallTrace = listOf(
        "ASSIGN 3 reason=F",
        "RESULT UNSAT",
    )

    private val conflictSmallTrace = listOf(
        "DECIDE -5",
        "ASSIGN -3 reason=C",
        "ASSIGN -2 reason=C",
        "ASSIGN -1 reason=C",
        "CONFLICT",
        "ASSIGN 3 reason=F",
        "ASSIGN 5 reason=F",
        "ASSIGN 4 reason=F",
        "RESULT UNSAT",
    )

    private val conflictSatTrace = listOf(
        "DECIDE -6",
        "ASSIGN 2 reason=C",
        "ASSIGN -4 reason=C",
        "ASSIGN 3 reason=C",
        "ASSIGN 5 reason=C",
        "CONFLICT",
        "ASSIGN 6 reason=F",
        "ASSIGN -5 reason=F",
        "ASSIGN 4 reason=F",
        "DECIDE 2",
        "ASSIGN 3 reason=C",
        "DECIDE -1",
        "RESULT SAT",
    )

    private val php32Trace = listOf(
        "DECIDE -6",
        "ASSIGN 5 reason=C",
        "ASSIGN -3 reason=C",
        "ASSIGN -1 reason=C",
        "ASSIGN 4 reason=C",
        "ASSIGN 2 reason=C",
        "CONFLICT",
        "ASSIGN -5 reason=F",
        "ASSIGN 6 reason=F",
        "ASSIGN -4 reason=F",
        "ASSIGN -2 reason=F",
        "ASSIGN 3 reason=F",
        "ASSIGN 1 reason=F",
        "RESULT UNSAT",
    )

    private fun runShadow(
        name: String,
        cnfText: String,
        expectedTrace: List<String>,
        expectedResult: SatResult,
    ) {
        val cnf = DimacsCnf.parse(cnfText)
        val solver = MicroSat(cnf.numVars)
        val trace = ArrayList<String>()
        solver.setTraceSink { trace.add(it) }

        for (clause in cnf.clauses) solver.addClause(clause)
        val result = solver.solve()

        assertEquals(expectedTrace, trace, "[$name] Kotlin trace differs from C reference trace")
        assertEquals(expectedResult, result, "[$name] SAT/UNSAT verdict mismatch")

        if (result == SatResult.SAT) {
            assertTrue(
                cnf.isSatisfiedBy { v -> solver.valueOf(v) },
                "[$name] returned model does not satisfy the CNF",
            )
        }
    }

    @Test
    fun satSmallMatchesReference() =
        runShadow("sat_small", satSmall, satSmallTrace, SatResult.SAT)

    @Test
    fun unsatSmallMatchesReference() =
        runShadow("unsat_small", unsatSmall, unsatSmallTrace, SatResult.UNSAT)

    @Test
    fun conflictSmallMatchesReference() =
        runShadow("conflict_small", conflictSmall, conflictSmallTrace, SatResult.UNSAT)

    @Test
    fun conflictSatMatchesReference() =
        runShadow("conflict_sat", conflictSat, conflictSatTrace, SatResult.SAT)

    @Test
    fun php32MatchesReference() =
        runShadow("php_3_2", php32, php32Trace, SatResult.UNSAT)
}
