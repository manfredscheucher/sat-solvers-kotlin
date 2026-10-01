# sat-solvers-kotlin

[Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) ports of four C/C++ SAT
solvers — microSAT, MiniSat, CaDiCaL and kissat — behind one uniform `Ksat` interface. Plain
Kotlin common code (no FFI, no bundled native library), so it builds for every KMP target: JVM,
Android, native (iOS, macOS, Linux, Windows) and JavaScript/Wasm.

Each solver is also its own standalone repo (`microsat-kotlin`, `minisat-kotlin`,
`cadical-kotlin`, `kissat-kotlin`), pulled in here as a submodule.

## Use a solver

```kotlin
val s = Ksat(Solver.CADICAL, numVars = 3)   // or MICROSAT / MINISAT / KISSAT
s.addClause(intArrayOf(1, 2, 3))            // signed DIMACS literals: +v true, -v false
s.addClause(intArrayOf(-1, 2))

// solve under assumptions, then read the model (MICROSAT has no assumptions path)
if (s.solve(assumptions = intArrayOf(-1)) == SatResult.SAT) {
    val var2 = s.valueOf(2)
}
```

Variables are `1..numVars`; a literal is a signed variable. All four solvers take the same CNF
and return the same answer, so switching is a one-word change. `solve(assumptions)` forces those
literals for one solve and backtracks, so you can keep one solver and query it repeatedly
(solve-under-assumptions, like IPASIR / PySAT). Package namespace `org.bytefred.ksat`.

## Build

```bash
git clone --recursive https://github.com/manfredscheucher/sat-solvers-kotlin.git
cd sat-solvers-kotlin
./gradlew jvmTest
```

## ksat-extra (optional)

An optional submodule [ksat-extra](https://github.com/manfredscheucher/ksat-extra) adds the fun
parts, not pulled by default:

- a **multiplatform demo** you can run on JVM, in the browser, on Android and iOS (with screenshots),
- **benchmarks** comparing each Kotlin port's speed against the original C solver,
- the **shadowing harness** that checks each port behaves identically to its C original, plus the docs.

```bash
git submodule update --init --checkout ksat-extra
```

## License

MIT, see [LICENSE](LICENSE). Each port is a derivative work of an MIT-licensed original
(microSAT © Marijn Heule; MiniSat © Niklas Eén & Niklas Sörensson; CaDiCaL and kissat © Armin
Biere and contributors); original license texts are under `licenses/`.

Ported with the help of Claude (Anthropic).
