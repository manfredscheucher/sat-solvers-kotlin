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

- `ksat-common/` is the `SatSolver` interface, `SatResult`, `Traceable`, and the
  DIMACS parser. Every port implements this.
- `microsat/` is microSAT (Marijn Heule). The smallest one; its heuristics are all
  integer, so its trace matches the C exactly.
- `minisat/` is MiniSat core CDCL — conflict-driven clause learning, the algorithm
  all four use (Één, Sörensson).
- `cadical/` is CaDiCaL core (Biere et al.).
- `kissat/` is kissat core (Biere et al.).
- `ksat/` is one entry point, `Ksat`, that picks a solver at runtime (see below).
- `shadow/` holds the C references (the verbatim originals and an instrumented copy
  that prints the trace), the test CNFs, and the scripts that build and diff them.

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
```

Variables are `1..numVars`; a literal is a signed variable (`3` = var 3 true, `-3` =
var 3 false). `solve(assumptions)` forces those literals for one solve and then backtracks,
so you can keep one solver around and query it many times without reloading the clauses
(the same solve-under-assumptions style as IPASIR and PySAT). Package namespace is
`org.bytefred.ksat`.

## Build & run

Requires a JDK and the Gradle wrapper in this repo.

```bash
# run all tests (unit + the trace-comparison shadow tests)
./gradlew jvmTest

# regenerate the golden C traces the shadow tests compare against (needs a C++ compiler).
# One script per float solver (minisat, cadical, kissat) in shadow/tools/, each with an
# -assume variant; minisat shown here as an example.
bash shadow/tools/regen_golden_minisat.sh
bash shadow/tools/regen_golden_minisat_assume.sh   # the solve-under-assumptions traces
```

## License

MIT, see [LICENSE](LICENSE). Each port is a derivative work of an MIT-licensed original solver
(microSAT © Marijn Heule; MiniSat © Niklas Eén & Niklas Sörensson; CaDiCaL and kissat © Armin
Biere and contributors). Original license texts are preserved under `licenses/`, and the
per-solver attribution is in [LICENSE](LICENSE) and each source file's header. The original
C/C++ solver sources are included under `shadow/` as references for the shadow tests.

## Development

Ported with the help of Claude (Anthropic).
