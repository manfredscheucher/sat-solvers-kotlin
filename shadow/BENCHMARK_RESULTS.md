# Runtime benchmark: Kotlin ports vs C (measured, not estimated)

Pigeonhole PHP(n+1, n) instances — UNSAT, pure CDCL search, no I/O bias. Generated with
`shadow/tools/gen_cnf.py` (up to php_8_7) and `shadow/tools/gen_big_php.py 8 9` (php_9_8, php_10_9).

Machine: Apple Silicon (arm64), macOS. C built with `clang++ -O2 -std=c++11`. Kotlin = JVM
(`:minisat:runBenchmark`), solve-only, median of 5 runs after 3 warm-ups. C number = whole-process
wall time (parse+solve+exit; parse/IO negligible vs solve here). Date: 2026-09.

## MiniSat: C vs Kotlin/JVM vs Kotlin/Native (macOS arm64, release -opt)

| instance   | C       | Kotlin/JVM | Kotlin/Native | JVM/C  | Native/C |
|------------|---------|------------|---------------|--------|----------|
| php_7_6    | 0.008 s | (sub-ms)   | —             | —      | —        |
| php_8_7    | 0.028 s | —          | —             | —      | —        |
| php_9_8    | 0.180 s | 0.239 s    | 0.650 s       | 1.33×  | 3.61×    |
| php_10_9   | 2.606 s | 3.526 s    | 9.641 s       | 1.35×  | 3.70×    |

**Kotlin/JVM ≈ 1.3× C** (competitive). **Kotlin/Native ≈ 3.7× C — and slower than the JVM**
(≈2.7× the JVM time). This is the known JIT-beats-AOT-on-hot-loops effect for an
allocation/array-heavy CDCL workload; see `doc/benchmarks.typ` for the full explanation.

Control (rules out an un-optimized build): the DEBUG native binary solves php_9_8 in
7860 ms vs the RELEASE binary's 650 ms (~12× slower), so 650 ms is genuinely `-opt` code.

## Notes / caveats

- This is **JVM** Kotlin (JIT-warmed), NOT Kotlin/Native. A Kotlin/Native-vs-C comparison would
  likely show a larger gap and is a separate measurement (not done here).
- CaDiCaL: the Kotlin CaDiCaL port solved php_9_8 in ~7.0 s (JVM), much slower than Kotlin MiniSat
  on the same instance. A fair Kotlin-CaDiCaL-vs-C-CaDiCaL ratio was NOT measured — the C
  `cadical_trace.cc` did not link standalone here (missing symbols), so no C baseline was taken.
  Do not read the 7 s as a port-quality number without the C baseline.
- Kotlin timings are solve-only; C includes parse+exit. For these instances parse is negligible,
  so the comparison is order-of-magnitude fair, slightly favouring C by its tiny parse overhead.
