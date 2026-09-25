# Splitting the solvers into their own repos

## What was asked

Move each solver port into its own GitHub repo and pull them back into the main repo as
git submodules. The sub-repos should be autonomous (standalone-buildable), each with a
consistent README. Keep everything else — the tests, the shadow harness, the docs, the
benchmarks and the `Ksat` facade — in the main repo, with the sub-repos linking back to it.

## Decisions made

- **Five sub-repos.** Four solvers plus the shared base:
  - `ksat-common` (git@github.com:manfredscheucher/ksat-common.git)
  - `microsat-kotlin`, `minisat-kotlin`, `cadical-kotlin`, `kissat-kotlin`
    (git@github.com:manfredscheucher/&lt;name&gt;.git)
- **Naming.** The solver repos carry a `-kotlin` suffix because they are Kotlin ports of a
  same-named C solver (so `kissat-kotlin` vs. the C `kissat`). `ksat-common` has no C
  original — it is project-internal infrastructure — so it gets no suffix. The `k` in
  `ksat` is the project name (`org.bytefred.ksat`), not "Kotlin"; the code namespace is
  unchanged.
- **`ksat-common` is its own repo, not a copy.** `SatSolver` / `Traceable` is a shared type
  contract that all four solvers implement. Copying it into each repo would create four
  distinct `org.bytefred.ksat.SatSolver` types that collide (or diverge) once the main repo
  builds them together. So it is one repo, embedded as a **nested** git submodule in each
  solver repo, mounted at `common/`.
- **Mount path `common/`.** In every repo the `ksat-common` submodule is mounted at
  `common/`, not `ksat-common/`, so it reads as `<repo>/common`.
- **Sub-repos contain only the port source** (`src/commonMain/.../<Solver>.kt`) plus the
  gradle scaffolding needed to build standalone. The benchmarks (`jvmMain`), the tests
  (`commonTest` + `jvmTest`, including the shadow tests), the `shadow/` dir, the `ksat`
  facade and `doc/` all stay in the main repo.

## Main-repo wiring (avoids the duplicate-`ksat-common` trap)

The hazard: if the main build ever saw two `ksat-common` Gradle projects (the top-level one
and a nested copy inside a solver submodule), the `org.bytefred.ksat` package would be on the
classpath twice and the facade's `SatSolver` would differ from the solver's. Avoided by:

- The main `settings.gradle.kts` binds `:ksat-common` **once** (to the top-level `common/`
  submodule) and includes each solver once. The nested `common/` inside each solver
  submodule is **never** included in the main build — it exists only so the solver builds
  standalone.
- Each solver module in the main repo keeps a **thin** `build.gradle.kts` that:
  - points its `commonMain` `srcDirs` at the submodule's `src/commonMain/...` (the port),
  - keeps `commonTest` / `jvmTest` `srcDirs` in the main repo (the shadow + sanity tests),
  - declares `implementation(project(":ksat-common"))` → the single top-level one.
- `srcDirs` are pinned to the exact `src/<sourceSet>` path, never a parent, so the nested
  `common/` source is never swept into a solver module.

This keeps `includeBuild` (composite builds) out of it — plain multi-project `include` with a
`projectDir`/`srcDirs` override is the low-drama path for a shared KMP source-set graph.

## Standing footgun

The port lives in the solver submodule; its tests live in the main repo. A change to a port
needs a new commit in the solver repo **and** a bumped submodule pointer in the main repo
before the shadow/golden tests validate it. Running the main repo against a stale submodule
pointer looks green but tests old code. (Propagation between local checkouts follows the
usual local-submodule-sync path — fetch by path, no push needed for a local build.)

## Shadow trace scope: the large benchmark instances

The shadow tests iterate over every `shadow/cnf/*.cnf` that has a golden trace. Two of those,
`php_9_8` and `php_10_9`, are large BENCHMARK instances (`shadow/tools/gen_big_php.py`), added
for the runtime benchmark in `doc/benchmarks.typ`, not for trace verification. The test reads
each golden trace whole (`readLines`), so the biggest ones are memory- and time-heavy. Measured
per solver (full golden trace of php_10_9, JVM test fork):

| solver   | php_9_8 trace | php_10_9 trace | ShadowTraceFilesTest time | min test heap |
|----------|--------------:|---------------:|--------------------------:|--------------:|
| microsat |       0.7 MB  |         4.1 MB |                    ~0.6 s |     default   |
| minisat  |      12 MB    |       121 MB   |                    ~3.6 s |     7–8 GB    |
| kissat   |       8 MB    |        33 MB   |                    ~5.3 s |     default*  |
| cadical  |      40 MB    |       156 MB   |                   ~27 s   |     7–8 GB    |

\* kissat's php_10_9 (33 MB) fits the default heap; minisat/cadical (121/156 MB) OOM below ~7 GB.

Decision: **php_9_8 is always compared** (small enough for the default heap). **php_10_9 is
opt-in** via `-Dbigtrace`, which the `jvmTest` task detects to bump the test heap to 8 GB:

```
./gradlew :cadical:jvmTest -Dbigtrace   # includes php_10_9, byte-for-byte (L1), ~45 s
./gradlew jvmTest                        # default: php_9_8 in, php_10_9 out, fast
```

This keeps the default test run fast and within the daemon RAM budget while leaving the full
byte-for-byte coverage of the largest instance available on demand. The `BENCHMARK_ONLY` set in
each `ShadowTraceFilesTest.kt` and the `-Dbigtrace` wiring in each solver `build.gradle.kts`
implement this. Measured with `scripts/measure-shadow-big-instances.sh`.

## Remote steps (Manfred runs these — all remote git is his)

See `scripts/README-repo-split.md` for the exact create/init/push/submodule-add sequence.
The local staging (source, build files, READMEs, LICENSEs) is already prepared under
`~/github/<repo>` by `scripts/stage-solver-subrepos.sh` + `write-solver-buildfiles.sh` +
`write-solver-readmes.sh`.
