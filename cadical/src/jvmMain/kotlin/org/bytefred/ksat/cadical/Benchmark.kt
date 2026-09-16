package org.bytefred.ksat.cadical

import org.bytefred.ksat.DimacsCnf
import org.bytefred.ksat.SatResult
import java.io.File
import kotlin.system.measureNanoTime

/**
 * Runtime benchmark for the Kotlin [CaDiCaL] (core) port (JVM).
 *
 * Solves each CNF passed on the command line (or, with no args, every CNF under
 * `shadow/cnf/` found by walking up from the working dir) and prints one line per
 * instance with the verdict and the median solve time over a few timed runs after
 * warm-up (the JVM JIT needs warming before numbers mean anything).
 *
 * The companion script `shadow/tools/benchmark_cadical.sh` builds the C reference
 * (`c++ -O2 -std=c++17 cadical_trace.cc`), times it on the SAME CNFs, and prints a
 * side-by-side table so we can confirm the port is within a small constant factor of
 * C. No perf claims without these measured numbers.
 *
 * Usage: ./gradlew :cadical:runBenchmark
 */
object Benchmark {

    private const val WARMUP = 3
    private const val RUNS = 5

    @JvmStatic
    fun main(args: Array<String>) {
        val files: List<File> = if (args.isNotEmpty()) {
            args.map { File(it) }
        } else {
            val cnfDir = locateCnfDir()
            if (cnfDir == null) {
                System.err.println("no CNFs given and no shadow/cnf dir found")
                return
            }
            cnfDir.listFiles { f -> f.name.endsWith(".cnf") }?.sortedBy { it.name } ?: emptyList()
        }

        println("# Kotlin CaDiCaL (core) benchmark (solve-only, median of $RUNS runs, $WARMUP warm-up)")
        println("%-26s %-6s %12s".format("cnf", "result", "solve_ms"))
        for (f in files) {
            if (!f.isFile) {
                println("%-26s %-6s %12s".format(f.name, "MISSING", "-"))
                continue
            }
            val (result, medianMs) = timeSolve(f)
            println("%-26s %-6s %12.3f".format(f.name, result, medianMs))
        }
    }

    private fun timeSolve(file: File): Pair<String, Double> {
        val text = file.readText()
        val cnf = DimacsCnf.parse(text)

        fun once(): SatResult {
            val solver = CaDiCaL(cnf.numVars)
            for (clause in cnf.clauses) solver.addClause(clause)
            return solver.solve()
        }

        var result = SatResult.UNSAT
        repeat(WARMUP) { result = once() }

        val times = DoubleArray(RUNS)
        for (i in 0 until RUNS) {
            val ns = measureNanoTime { result = once() }
            times[i] = ns / 1_000_000.0
        }
        times.sort()
        val median = times[times.size / 2]
        return result.name to median
    }

    private fun locateCnfDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shadow/cnf")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
