# sat-solvers-kotlin

Kotlin Multiplatform (KMP) ports of a few well-known C/C++ SAT solvers: MicroSAT,
MiniSat, CaDiCaL and kissat. Each one is its own module.

KMP means one Kotlin codebase compiles to several targets: the JVM (so also
Android), native iOS/macOS/Linux/Windows through Kotlin/Native, and the browser
via JavaScript or WebAssembly. The solver code here is plain Kotlin with no
platform APIs, no FFI and no native library to link, so it can run on any of
these. Right now the build is set up for the JVM and iOS; the other targets
(Android, JS, Wasm, the remaining native ones) only need to be added to the
Gradle config, they don't need code changes. I only test JVM and iOS, so treat
the rest as "should work" until tried.

I did not write new solvers here. Each port is a line-by-line translation of the
upstream code, and I check every port against the original C: both run the same
CNF and I compare their traces step by step (which variable is decided, which
clause propagates, where a conflict happens), not just the final SAT/UNSAT answer.
The procedure and the current status per solver are in `doc/shadowing-methodology`.

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
the same order as the C, which is what the ports do. Details and the known limits
are in `doc/shadowing-methodology`.

## License

MIT, see [LICENSE](LICENSE). These are ports, so each one keeps its upstream
copyright and MIT notice; those are in [`licenses/`](licenses/).
