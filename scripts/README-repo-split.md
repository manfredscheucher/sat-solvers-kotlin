# Repo split — the remote steps (Manfred runs these)

All local staging is done. Everything below touches GitHub / git remotes, so **Manfred runs
it** (Claude never pushes, clones, fetches, or adds submodules). The order matters because
of the nested submodule: `ksat-common` must be on GitHub before it can be added into the
solver repos, and the solver repos must be on GitHub before they can be added into the main
repo.

Local staging already prepared under `~/github/`:
`ksat-common/`, `microsat-kotlin/`, `minisat-kotlin/`, `cadical-kotlin/`, `kissat-kotlin/`
(source + gradle + README + LICENSE). `microsat-kotlin` was test-compiled standalone; the
main-repo srcDir wiring was verified by `scripts/verify-srcdir-wiring.sh`.

Create the five empty repos on GitHub first (no README/license — they're already staged):
`ksat-common`, `microsat-kotlin`, `minisat-kotlin`, `cadical-kotlin`, `kissat-kotlin`.

## 1. Publish ksat-common

```bash
cd ~/github/ksat-common
git init
git add -A
git commit -m "ksat-common: SatSolver/Traceable/SatResult + DIMACS parser"
git branch -M main
git remote add origin git@github.com:manfredscheucher/ksat-common.git
git push -u origin main
```

## 2. Publish each solver repo, with ksat-common as a nested submodule at common/

The staging put a plain copy at `common/`. Replace it with a real submodule. Do this for
each of the four (microsat shown; repeat for minisat, cadical, kissat):

```bash
cd ~/github/microsat-kotlin
rm -rf common                     # drop the staged plain copy
git init
git submodule add git@github.com:manfredscheucher/ksat-common.git common
git add -A
git commit -m "microsat-kotlin: standalone Kotlin microSAT port (ksat-common as submodule)"
git branch -M main
git remote add origin git@github.com:manfredscheucher/microsat-kotlin.git
git push -u origin main
```

Repeat for `minisat-kotlin`, `cadical-kotlin`, `kissat-kotlin` (change both the dir and the
`origin` URL). After each, optionally verify standalone:

```bash
./gradlew compileKotlinJvm
```

## 3. Wire the submodules into the main repo

In the main repo, add the shared ksat-common once (mounted at common/) and each solver port
(mounted at <solver>/port/). Then run the cutover script and the tests.

```bash
cd ~/github/sat-solvers-kotlin
git submodule add git@github.com:manfredscheucher/ksat-common.git common
git submodule add git@github.com:manfredscheucher/microsat-kotlin.git microsat/port
git submodule add git@github.com:manfredscheucher/minisat-kotlin.git  minisat/port
git submodule add git@github.com:manfredscheucher/cadical-kotlin.git  cadical/port
git submodule add git@github.com:manfredscheucher/kissat-kotlin.git   kissat/port
git submodule update --init --recursive   # pulls each solver's nested common/

bash scripts/cutover-main-to-submodules.sh   # deletes old copies, writes thin build files
./gradlew jvmTest                             # shadow tests against the submodule ports
```

Only when `jvmTest` is green:

```bash
git add -A
git commit -m "split solvers into submodules; consume ports via common/ + <solver>/port"
git push
```

## Notes

- `git clone` of the main repo later needs `--recursive` (nested submodules).
- To point the main repo's ksat-common submodule at your local ksat-common checkout instead
  of GitHub (your local-submodule-sync workflow), edit `.git/config`, not `.gitmodules`.
- If `cutover-main-to-submodules.sh` complains a mount is missing, a `git submodule add`
  didn't land — re-check step 3.
