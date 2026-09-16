package org.bytefred.ksat.microsat

import org.bytefred.ksat.DimacsCnf
import org.bytefred.ksat.SatResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * File-driven shadow test (JVM only, needs filesystem access).
 *
 * For every `shadow/cnf/<name>.cnf` that has a matching golden trace
 * `shadow/golden/<name>.trace`, run the Kotlin [MicroSat] with a trace sink and
 * compare its trace line-for-line against the golden trace produced by the
 * instrumented C reference. Golden traces are regenerated (and the CNFs
 * generated) by `shadow/tools/regen_golden.sh`.
 *
 * This complements the embedded-string [ShadowTraceTest] (which runs on all
 * targets) by covering the bigger, generated instances -- in particular the
 * ones that exercise the RESTART / REDUCE code paths. Those cases are asserted
 * to actually contain RESTART/REDUCE lines so a regression that stops hitting
 * them is caught (see [restartReducePathsAreCovered]).
 */
class ShadowTraceFilesTest {

    private val shadowDir: File? by lazy { locateShadowDir() }

    private fun locateShadowDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shadow")
            if (File(candidate, "cnf").isDirectory && File(candidate, "golden").isDirectory) {
                return candidate
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun cases(shadow: File): List<Pair<File, File>> {
        val cnfDir = File(shadow, "cnf")
        val goldDir = File(shadow, "golden")
        return (cnfDir.listFiles { f -> f.name.endsWith(".cnf") }?.sortedBy { it.name } ?: emptyList())
            .mapNotNull { cnf ->
                val gold = File(goldDir, cnf.nameWithoutExtension + ".trace")
                if (gold.isFile) cnf to gold else null
            }
    }

    private fun runOne(cnfFile: File, goldFile: File) {
        val cnf = DimacsCnf.parse(cnfFile.readText())
        val expectedTrace = goldFile.readLines().filter { it.isNotEmpty() }

        val solver = MicroSat(cnf.numVars)
        val trace = ArrayList<String>()
        solver.setTraceSink { trace.add(it) }
        for (clause in cnf.clauses) solver.addClause(clause)
        val result = solver.solve()

        assertEquals(
            expectedTrace, trace,
            "[${cnfFile.name}] Kotlin trace differs from C golden trace",
        )

        // Derive the expected verdict from the golden trace's RESULT line.
        val resultLine = expectedTrace.lastOrNull { it.startsWith("RESULT ") }
            ?: fail("[${cnfFile.name}] golden trace has no RESULT line")
        val expectedResult =
            if (resultLine == "RESULT SAT") SatResult.SAT else SatResult.UNSAT
        assertEquals(expectedResult, result, "[${cnfFile.name}] verdict mismatch")

        if (result == SatResult.SAT) {
            assertTrue(
                cnf.isSatisfiedBy { v -> solver.valueOf(v) },
                "[${cnfFile.name}] returned model does not satisfy the CNF",
            )
        }
    }

    @Test
    fun allGoldenTracesMatch() {
        val shadow = shadowDir ?: run {
            // No golden traces available (e.g. regen_golden.sh not run yet). Skip
            // rather than fail so a fresh checkout without generated files is green.
            println("[shadow-files] no shadow/golden dir found; run shadow/tools/regen_golden.sh -- skipping")
            return
        }
        val cases = cases(shadow)
        if (cases.isEmpty()) {
            println("[shadow-files] no golden traces found; run shadow/tools/regen_golden.sh -- skipping")
            return
        }
        for ((cnf, gold) in cases) runOne(cnf, gold)
    }

    /**
     * Reports whether any *default-threshold* generated instance drives MicroSAT's
     * RESTART / REDUCE paths.
     *
     * With the upstream `maxLemmas=2000` and restart heuristic (`fast > slow/100*125`),
     * no *fast* instance reaches REDUCE — even php_8_7 (13k trace events) triggers
     * neither. Rather than add an impractically large/slow instance, REDUCE is now
     * trace-covered by [lowMaxLemmasForcesReduce] using the `maxLemmasInit`
     * (Kotlin) / `LSMAXLEMMAS` (C) test-only knob, which shadows byte-for-byte at a
     * tiny `maxLemmas`. This test therefore only *reports* the default-threshold
     * coverage (plus whether the low-maxLemmas golden carries REDUCE); it never fails.
     */
    @Test
    fun restartReduceCoverageReport() {
        val shadow = shadowDir ?: return
        val cases = cases(shadow)
        if (cases.isEmpty()) return

        var sawRestart = false
        var sawReduce = false
        for ((_, gold) in cases) {
            val lines = gold.readLines()
            if (lines.contains("RESTART")) sawRestart = true
            if (lines.contains("REDUCE")) sawReduce = true
        }
        // The low-maxLemmas goldens (below) are the ones that actually cover REDUCE.
        val lowLemmasReduce = File(File(shadow, "golden"), "php_5_4_lemmas3.trace")
            .takeIf { it.isFile }?.readLines()?.contains("REDUCE") == true
        println(
            "[microsat coverage] RESTART=${if (sawRestart) "yes" else "no (only via low-maxLemmas goldens)"} " +
                "REDUCE=${if (sawReduce || lowLemmasReduce) "yes (php_5_4_lemmas3 forces reduceDB)" else "no"}",
        )
    }

    /**
     * Trace-covers MicroSAT's REDUCE path (reduceDB), which the default
     * `maxLemmas=2000` never reaches on any fast instance. We drop the initial
     * `maxLemmas` to 3 via [MicroSat]'s `maxLemmasInit` knob and shadow against the
     * C reference re-traced with `LSMAXLEMMAS=3` (golden `php_5_4_lemmas3.trace`,
     * regenerated by shadow/tools/regen_golden.sh). This finally exercises the
     * dual-location watch-pointer compaction in `reduceDB` — the single most
     * error-prone watched-literal translation pattern — byte-for-byte against C.
     *
     * If the Kotlin trace ever diverges from the C golden here, it is exactly the
     * reduceDB port bug the methodology warns about: debug MicroSat.reduceDB.
     */
    /**
     * Trace-covers MicroSAT's REDUCE + RESTART paths. Upstream `maxLemmas=2000` +
     * `restartFactor=125` never reach reduceDB on a fast instance, and REDUCE is gated
     * behind RESTART in `solve()`, so BOTH knobs must be lowered: `maxLemmasInit=3` and
     * `restartFactorInit=20`. The C golden (`php_5_4_reduce.trace`) is generated with the
     * matching `LSMAXLEMMAS=3 LSRESTARTFACTOR=20`. This finally exercises the dual-location
     * watch-pointer compaction in reduceDB — the most error-prone watched-literal pattern —
     * byte-for-byte against C. If it diverges, that IS the reduceDB port bug the methodology
     * warns about.
     */
    @Test
    fun knobsForceReduceAndRestart() {
        val shadow = shadowDir ?: run {
            println("[shadow-files] no shadow dir; run shadow/tools/regen_golden.sh -- skipping")
            return
        }
        val cnfFile = File(File(shadow, "cnf"), "php_5_4.cnf")
        val goldFile = File(File(shadow, "golden"), "php_5_4_reduce.trace")
        if (!cnfFile.isFile || !goldFile.isFile) {
            println("[shadow-files] missing php_5_4_reduce golden; run shadow/tools/regen_golden.sh -- skipping")
            return
        }

        val cnf = DimacsCnf.parse(cnfFile.readText())
        val expectedTrace = goldFile.readLines().filter { it.isNotEmpty() }

        val solver = MicroSat(cnf.numVars, maxLemmasInit = 3, restartFactorInit = 20)
        val trace = ArrayList<String>()
        solver.setTraceSink { trace.add(it) }
        for (clause in cnf.clauses) solver.addClause(clause)
        val result = solver.solve()

        assertEquals(
            expectedTrace, trace,
            "[php_5_4 reduce] Kotlin trace differs from C golden -- likely a reduceDB port bug",
        )
        assertTrue(expectedTrace.contains("REDUCE"), "golden must contain REDUCE")
        assertTrue(expectedTrace.contains("RESTART"), "golden must contain RESTART")
        assertEquals(SatResult.UNSAT, result, "[php_5_4 reduce] verdict mismatch")
    }
}
