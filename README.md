# sat-solvers-kotlin

[Kotlin Multiplatform](https://en.wikipedia.org/wiki/Kotlin_(programming_language)#Kotlin_Multiplatform)
ports of four well-known C/C++ SAT solvers: microSAT, MiniSat, CaDiCaL and kissat.

Kotlin Multiplatform (KMP) is a superset of Kotlin, not JVM-only Kotlin: the same source
compiles to many targets. These solvers are plain KMP common code — no calls into the C
solvers, no bundled native library — so every module builds for all of them: JVM, Android,
native (iOS, macOS, Linux, Windows), and JavaScript/Wasm. Each solver is its own module.

Each solver is a line-by-line port of an existing C/C++ solver, checked against the original's
full solver trace across a range of tests (see [How the ports were done](#how-the-ports-were-done)).

## Modules

- `ksat-common/` (Gradle module `:ksat-common`) is the `SatSolver` interface, `SatResult`,
  `Traceable`, and the DIMACS parser. Every port implements this.
- `solver/microsat/` is microSAT (Marijn Heule). The smallest one; its heuristics are all
  integer, so its trace matches the C exactly.
- `solver/minisat/` is MiniSat core CDCL — conflict-driven clause learning, the algorithm
  all four use (Één, Sörensson).
- `solver/cadical/` is CaDiCaL core (Biere et al.).
- `solver/kissat/` is kissat core (Biere et al.).
- Each `solver/<name>/` is a thin wrapper (tests + build) around its `<name>-kotlin`
  submodule, which holds the port source.
- `ksat/` is one entry point, `Ksat`, that picks a solver at runtime (see below).
- `ksat-extra/` is an **optional** submodule (a default `git clone --recursive` skips it)
  holding the shadow harness: the C references (verbatim originals + instrumented trace
  copies), the test CNFs, the benchmark data, and the scripts that build and diff them.
  Pull it in with `git submodule update --init ksat-extra`.

## How the ports were done

Each port is written line by line against the original C. To check it actually behaves the
same at runtime, not just on the final SAT/UNSAT answer, an instrumented build of both the C
and the Kotlin prints a trace of every decision, propagation and conflict, and the tests
compare the two traces. When they match, the port took the same steps in the same order with
the same values as the C.

For the integer-heuristic solver (microSAT) the traces match exactly. For the ones with
`double` VSIDS (variable-activity) scores they match as long as the float arithmetic runs
in the same order as the C, which is what the ports do. The float width is a per-solver
option (32- or 64-bit), defaulting to whatever the original C uses so the traces line up —
32-bit for MiniSat, 64-bit for CaDiCaL and kissat. The methodology, the known limits, and the
per-solver status are in [`doc/shadowing-methodology.pdf`](doc/shadowing-methodology.pdf); a
runtime comparison of C vs Kotlin/JVM vs Kotlin/Native is in
[`doc/benchmarks.pdf`](doc/benchmarks.pdf).

## Picking a solver

The three larger solvers take the same CNF (the clause set to solve) and give back the
same answer, so switching between them is a one-word change (microSAT is ported and shadow-tested
too, but not wired into the `Ksat` picker):

```kotlin
val s = Ksat(Solver.CADICAL, numVars = 3)   // or MINISAT / KISSAT
s.addClause(intArrayOf(1, 2, 3))            // clauses are signed DIMACS literals:
s.addClause(intArrayOf(-1, 2))              // +v means "var v true", -v means "false"

// solve assuming var 1 is false (assumptions are literals, not just variables)
if (s.solve(assumptions = intArrayOf(-1)) == SatResult.SAT) {
    val var2 = s.valueOf(2)   // read var 2 in the model of this solve
}

// same instance, no clause reload: ask again under different assumptions
if (s.solve(assumptions = intArrayOf(1)) == SatResult.SAT) { /* ... */ }
val plain = s.solve()                       // or with no assumptions at all
```

Variables are `1..numVars`; a literal is a signed variable (`3` = var 3 true, `-3` =
var 3 false). `solve(assumptions)` forces those literals for one solve and then backtracks,
so you can keep one solver around and query it many times without reloading the clauses
(the same solve-under-assumptions style as IPASIR and PySAT). Package namespace is
`org.bytefred.ksat`.

## Build & run

Requires a JDK and the Gradle wrapper in this repo. The solvers and the shared
`ksat-common` are git submodules, so clone recursively (a plain `git clone` leaves
them empty and the build fails with `No matching variant of project :ksat-common`):

```bash
git clone --recursive https://github.com/manfredscheucher/sat-solvers-kotlin.git
cd sat-solvers-kotlin
```

Already cloned without `--recursive`? Pull the submodules in:

```bash
git submodule update --init --recursive
```

Then build and test:

```bash
# run all tests (unit + the trace-comparison shadow tests)
./gradlew jvmTest
```

The shadow harness lives in the optional `ksat-extra` submodule, which a default
`git clone --recursive` does NOT pull. Without it (or without the golden traces, which are
regenerable and not checked in) the shadow tests skip their byte-for-byte comparison and
`jvmTest` is still green, so a fresh clone passes out of the box. To actually run the trace
comparison, check out `ksat-extra` and regenerate the goldens (needs a C++ compiler) — one
script per float solver in `ksat-extra/shadow/tools/`, each with an `-assume` variant;
minisat shown here:

```bash
git submodule update --init ksat-extra
bash ksat-extra/shadow/tools/regen_golden_minisat.sh
bash ksat-extra/shadow/tools/regen_golden_minisat_assume.sh   # solve-under-assumptions traces
```

The big php_10_9 instance is compared only with `-Dbigtrace` (it needs ~8 GB test heap):

```bash
./gradlew :minisat:jvmTest -Dbigtrace
```

## License

MIT, see [LICENSE](LICENSE). Each port is a derivative work of an MIT-licensed original solver
(microSAT © Marijn Heule; MiniSat © Niklas Eén & Niklas Sörensson; CaDiCaL and kissat © Armin
Biere and contributors). Original license texts are preserved under `licenses/`, and the
per-solver attribution is in [LICENSE](LICENSE) and each source file's header. The original
C/C++ solver sources are included in the `ksat-extra` submodule (`ksat-extra/shadow/`) as
references for the shadow tests.

## Development

Ported with the help of Claude (Anthropic).
