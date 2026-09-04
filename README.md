# sat-solvers-kotlin

> Sibling project: [sat-solvers-dafny](https://github.com/manfredscheucher/sat-solvers-dafny) —
> the same four solvers ported to Dafny, verified for memory safety.

[Kotlin Multiplatform](https://en.wikipedia.org/wiki/Kotlin_(programming_language)#Kotlin_Multiplatform)
ports of four well-known C/C++ SAT solvers: MicroSAT, MiniSat, CaDiCaL and kissat.
Each one is its own module. Being Multiplatform, the same code runs not only on the
JVM (and Android) but also on native iOS/macOS/Linux/Windows and in the browser — the
solvers are plain Kotlin, no FFI or native library. So far I build and test on the JVM
and iOS; the other targets just need to be enabled in the Gradle config.

I did not write new solvers. Each is a line-by-line translation of the original, and I
check every port against the C code: both run the same CNF and I compare their traces
step by step (which variable is decided, which clause propagates, where a conflict
happens), not just the final SAT/UNSAT answer. Details and per-solver status are in
`doc/` (start at [`doc/README.md`](doc/README.md)).

Package namespace: `org.bytefred.ksat`.

## Modules

- `ksat-common/` — the `SatSolver` interface, `SatResult`, `Traceable`, and the
  DIMACS parser. Every port implements this.
- `microsat/` — MicroSAT (Marijn Heule). The smallest one. It uses integer VMTF,
  so the trace matches the C byte for byte.
- `minisat/` — MiniSat core CDCL (Één, Sörensson).
- `cadical/` — CaDiCaL core (Biere et al.).
- `kissat/` — kissat core (Biere et al.).
- `ksat/` — one entry point, `Ksat`, that picks a solver at runtime (see below).
- `shadow/` — the C references (the verbatim originals and an instrumented copy
  that prints the trace), the test CNFs, and the scripts that build and diff them.

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
clauses (the same incremental interface PySAT and IPASIR use).

## Why byte-for-byte

Matching only SAT/UNSAT hides bugs: a port can reach the right answer on a
different search and still be wrong on the next instance. Comparing the trace
pins down the same decisions, propagations and conflicts in the same order. For
the integer-heuristic solver (MicroSAT) this holds exactly; for the ones with
`double` VSIDS activities it holds as long as the float arithmetic is written in
the same order as the C, which is what the ports do. The known limits are in
`doc/shadowing-methodology`.

## License

MIT — see [LICENSE](LICENSE). Each port is a derivative work of an MIT-licensed original solver
(microSAT © Marijn Heule; MiniSat © Niklas Eén & Niklas Sörensson; CaDiCaL and kissat © Armin
Biere and contributors). Original license texts are preserved under `licenses/`, and the
per-solver attribution is in [LICENSE](LICENSE) and each source file's header. The original
C/C++ solver sources are included under `shadow/` as references for the byte-for-byte shadow tests.

## Development

Ported with the help of Claude (Anthropic). Every port is checked against its C
original by the shadow tests in this repo, so correctness rests on those tests,
not on how the code was written.
