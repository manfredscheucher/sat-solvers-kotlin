# Independent upstream oracle for the MiniSat shadow

Reviewer W4: our `shadow/minisat-c/minisat_trace.cc` is a hand-transcription of
upstream MiniSat. Nothing checks it against the *real* solver, so a shared
transcription bug could make `Kotlin == trace == wrong` with no alarm. For MicroSAT
we solve this with a verbatim-upstream verdict oracle
(`validate_upstream_microsat.sh`) because upstream `microsat.c` is a single
compilable file. Real MiniSat is heavier (needs `mtl/` + `zlib` + its own build
system), so we do **not** build it in this repo. Instead we validate against a real
`minisat` binary *when one is available*.

## How to enable the oracle

Get a real `minisat` on your `PATH` (any one):

- macOS: `brew install minisat`
- Debian/Ubuntu: `sudo apt-get install minisat`
- from source: <https://github.com/niklasso/minisat>
  ```sh
  git clone https://github.com/niklasso/minisat && cd minisat
  mkdir build && cd build && cmake .. && make
  # put the built `minisat` binary on your PATH
  ```

Then run the harness:

```sh
bash shadow/tools/validate_upstream_minisat.sh
```

It compiles our instrumented `minisat_trace.cc` (if stale), runs real `minisat`
on every `shadow/cnf/*.cnf`, and diffs the SAT/UNSAT verdict against our
reference's `s SATISFIABLE` / `s UNSATISFIABLE`. Verdict only — upstream has no
`LSTRACE` and its search order need not match ours. Exit codes: real minisat
returns 10 for SAT and 20 for UNSAT, which the harness reads directly.

If no `minisat` is on `PATH`, the harness prints a skip message and exits 0, so it
never blocks a machine that doesn't have it.

## Why not full trace equality against upstream?

Upstream MiniSat emits no per-decision trace, and (unlike our instrumented copy)
we can't add one without editing/building it. The *decision order* between two
MiniSat builds can also differ with compiler/float details. So the upstream oracle
here is a **verdict** oracle: it catches a transcription bug that flips SAT/UNSAT
or produces a wrong answer. Byte-for-byte decision equality remains the job of the
in-repo shadow test (Kotlin vs our instrumented `minisat_trace`), which is L1-gated.

## Next hardening step (DRAT, L3)

For UNSAT instances the strongest independent check is a DRAT proof verified by
`drat-trim`. MicroSAT upstream has a DRAT-emitting variant; wiring it as a
permanent L3 gate for UNSAT is the intended next step (see
`doc/shadowing-methodology.typ`). It is not yet installed here (`drat-trim` is not
on this machine), so it is documented rather than wired.
