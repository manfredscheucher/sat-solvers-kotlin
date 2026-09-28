package org.bytefred.ksat.minisat

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import org.bytefred.ksat.DimacsCnf
import org.bytefred.ksat.SatResult
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.time.TimeSource

/**
 * Kotlin/Native (macOS arm64) runtime benchmark for the [MiniSat] port — the native counterpart of
 * jvmMain/Benchmark.kt, for a real native-binary-vs-C comparison (no JVM, no JIT).
 *
 * Reads each CNF path from argv, parses it, builds a MiniSat, and prints the median solve time over
 * a few runs. Built as an executable (see build.gradle.kts macosArm64 binaries).
 *
 * Run:  ./gradlew :minisat:linkReleaseExecutableMacosArm64
 *       ./minisat/build/bin/macosArm64/releaseExecutable/minisat.kexe <cnf>...
 */

private const val WARMUP = 3
private const val RUNS = 5

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): String? {
    val f = fopen(path, "rb") ?: return null
    try {
        fseek(f, 0, SEEK_END)
        val size = ftell(f)
        fseek(f, 0, SEEK_SET)
        if (size <= 0) return ""
        val buf = ByteArray(size.toInt())
        buf.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), f)
        }
        return buf.decodeToString()
    } finally {
        fclose(f)
    }
}

private fun timeSolve(cnf: DimacsCnf): Pair<SatResult, Double> {
    val times = DoubleArray(RUNS)
    var result = SatResult.UNSAT
    repeat(WARMUP + RUNS) { i ->
        val s = MiniSat(cnf.numVars, ActivityPrecision.FLOAT64)
        for (cl in cnf.clauses) s.addClause(cl)
        val mark = TimeSource.Monotonic.markNow()
        result = s.solve()
        val dtMs = mark.elapsedNow().inWholeNanoseconds / 1_000_000.0
        if (i >= WARMUP) times[i - WARMUP] = dtMs
    }
    times.sort()
    return result to times[times.size / 2]
}

fun main(args: Array<String>) {
    println("# Kotlin/Native (macOS) MiniSat benchmark (solve-only, median of $RUNS runs, $WARMUP warm-up)")
    println("cnf                        result  solve_ms")
    for (path in args) {
        val text = readFile(path)
        if (text == null) {
            println("$path  MISSING")
            continue
        }
        val cnf = DimacsCnf.parse(text)
        val (result, medianMs) = timeSolve(cnf)
        val name = path.substringAfterLast('/')
        // Manual fixed formatting (no String.format on Native).
        val ms = (medianMs * 1000).toLong() / 1000.0
        println("${name.padEnd(26)} ${result.name.padEnd(6)} $ms")
    }
}
