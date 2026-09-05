# sat-solvers-kotlin

[Kotlin Multiplatform](https://en.wikipedia.org/wiki/Kotlin_(programming_language)#Kotlin_Multiplatform)
ports of four well-known C/C++ SAT solvers: microSAT, MiniSat, CaDiCaL and kissat. The
solvers are plain Kotlin, no FFI and no native library, so the same code runs on the JVM
(and Android), on native iOS/macOS/Linux/Windows, and in the browser. Each solver is its
own module. So far I build and test on JVM and iOS; the other targets just need enabling
in the Gradle config.

Each solver is a line-by-line port of an existing one, checked against the original C
trace by trace, not just on the final SAT/UNSAT answer (see
[Why byte-for-byte](#why-byte-for-byte)). Package namespace is `org.bytefred.ksat`.
Details and per-solver status are in `doc/` (start at [`doc/README.md`](doc/README.md)).

## Modules

- `ksat-common/` is the `SatSolver` interface, `SatResult`, `Traceable`, and the
  DIMACS parser. Every port implements this.
- `microsat/` is microSAT (Marijn Heule). The smallest one. It uses integer VMTF,
  so the trace matches the C byte for byte.
- `minisat/` is MiniSat core CDCL (Één, Sörensson).
- `cadical/` is CaDiCaL core (Biere et al.).
- `kissat/` is kissat core (Biere et al.).
- `ksat/` is one entry point, `Ksat`, that picks a solver at runtime (see below).
- `shadow/` holds the C references (the verbatim originals and an instrumented copy
  that prints the trace), the test CNFs, and the scripts that build and diff them.

## Why byte-for-byte

Matching only SAT/UNSAT hides bugs: a port can reach the right answer on a
different search and still be wrong on the next instance. Comparing the trace
pins down the same decisions, propagations and conflicts in the same order. For
the integer-heuristic solver (microSAT) this holds exactly. For the ones with
`double` VSIDS activities it holds as long as the float arithmetic is written in
the same order as the C, which is what the ports do. The known limits are in
`doc/shadowing-methodology.typ`.

## Picking a solver

The three larger solvers take the same CNF and give back the same answer, so
switching between them is a one-word change:

```kotlin
val s = Ksat(Solver.CADICAL, numVars = n)   // or MINISAT / KISSAT
for (c in clauses) s.addClause(c)
if (s.solve(assumptions = intArrayOf(-x)) == SatResult.SAT) {
    val v = s.valueOf(x)   // model of this solve
}
```

`solve(assumptions)` forces the given literals for one solve and then backtracks,
so you can keep one solver around and query it many times without reloading the
clauses (the same solve-under-assumptions style as IPASIR and PySAT).

## Build & run

Requires a JDK and the Gradle wrapper in this repo.

```bash
# run all tests (unit + the byte-for-byte shadow tests)
./gradlew jvmTest

# regenerate the golden C traces the shadow tests compare against (needs a C++ compiler)
bash shadow/tools/regen_golden_minisat.sh
bash shadow/tools/regen_golden_minisat_assume.sh   # the solve-under-assumptions traces
```

## License

MIT, see [LICENSE](LICENSE). Each port is a derivative work of an MIT-licensed original solver
(microSAT © Marijn Heule; MiniSat © Niklas Eén & Niklas Sörensson; CaDiCaL and kissat © Armin
Biere and contributors). Original license texts are preserved under `licenses/`, and the
per-solver attribution is in [LICENSE](LICENSE) and each source file's header. The original
C/C++ solver sources are included under `shadow/` as references for the byte-for-byte shadow tests.

## Development

Ported with the help of Claude (Anthropic). Correctness rests on the tests in this
repo, not on how the code was written.
