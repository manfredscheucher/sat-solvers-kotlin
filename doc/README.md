# doc/ — sat-solvers-kotlin

The docs are written in Typst (`*.typ`, compiled to `*.pdf`).

- **shadowing-methodology** — how each port is checked against its C original: the
  step-by-step trace comparison, the per-solver comparison level, the lessons
  learned while porting, and the current status per solver.
- **shadow-test-setup** — the mechanics of the shadow harness (the instrumented C
  reference, the shared trace format, how traces are generated and diffed).
- **benchmarks** — measured runtime comparison C vs Kotlin/JVM vs Kotlin/Native on
  pigeonhole instances, and why Kotlin/Native is slower than the JVM here.
