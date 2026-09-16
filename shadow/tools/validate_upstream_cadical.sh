#!/usr/bin/env bash
# Independent oracle for the CaDiCaL shadow.
#
# Our instrumented reference shadow/cadical-c/cadical_trace.cc is a HAND-TRANSCRIPTION
# of the CaDiCaL CDCL core (inprocessing disabled). A shared transcription bug could
# make Kotlin == trace == wrong with nothing to catch it.
#
# This harness gives an independent verdict oracle WHEN a real `cadical` binary is
# available, without pulling in the full upstream build:
#   - if `cadical` is on PATH: run it on every shadow/cnf/*.cnf and diff its
#     SAT/UNSAT verdict against our cadical_trace's `s SATISFIABLE`/`s UNSATISFIABLE`.
#   - else: print a skip message and exit 0 (so it never blocks a machine without it).
#
# Getting a real cadical (pick one):
#   macOS:   brew install cadical
#   Debian:  sudo apt-get install cadical
#   source:  https://github.com/arminbiere/cadical  (./configure && make), then put the
#            `cadical` binary (build/cadical) on PATH.
#
# Real cadical prints "s SATISFIABLE"/"s UNSATISFIABLE" (exit code 10 = SAT,
# 20 = UNSAT). Our reference prints the same. We normalise both to SAT/UNSAT and
# compare. We do NOT compare full traces: upstream's full solver (with inprocessing)
# needn't take the same search path -- only the verdict must agree.
#
# Run:  bash shadow/tools/validate_upstream_cadical.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
cdir="$shadow/cadical-c"
trace_src="$cdir/cadical_trace.cc"
trace_bin="$cdir/cadical_trace"

if ! command -v cadical >/dev/null 2>&1; then
  echo "[skip] no 'cadical' binary on PATH -- upstream CaDiCaL oracle not run."
  echo "       Install one to enable it:  brew install cadical  (or apt-get, or build from source)."
  exit 0
fi
echo "[cadical oracle] found: $(command -v cadical)"

# Build our instrumented reference if the binary is missing/stale.
if [ ! -x "$trace_bin" ] || [ "$trace_src" -nt "$trace_bin" ]; then
  echo "[build] compiling instrumented reference cadical_trace.cc"
  "${CXX:-c++}" -O2 -std=c++17 -o "$trace_bin" "$trace_src" || {
    echo "FATAL: could not build $trace_src"; exit 2; }
fi

# Our reference: normalise "s SATISFIABLE"/"s UNSATISFIABLE" -> SAT/UNSAT.
ours_verdict() { grep -E '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}'; }

# Real cadical: prefer exit code (10 SAT / 20 UNSAT); fall back to the text line.
upstream_verdict() {
  local cnf="$1" out rc
  out="$(cadical -q "$cnf" 2>/dev/null)"; rc=$?
  case "$rc" in
    10) echo SAT ;;
    20) echo UNSAT ;;
    *)  printf '%s\n' "$out" | grep -Eo '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 \
          | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}' ;;
  esac
}

echo "[compare] our cadical_trace verdict vs real cadical on every CNF"
fail=0; n=0
printf '%-26s %-6s %-6s %s\n' "instance" "ours" "upstr" "status"
for cnf in "$cnfdir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  ours="$("$trace_bin" "$cnf" 2>/dev/null | ours_verdict)"
  upstr="$(upstream_verdict "$cnf")"
  status="ok"
  if [ -z "$upstr" ]; then status="upstream_no_verdict";
  elif [ "$ours" != "$upstr" ]; then status="MISMATCH"; fail=1; fi
  printf '%-26s %-6s %-6s %s\n' "$name" "$ours" "$upstr" "$status"
  n=$((n+1))
done

echo
if [ "$fail" -ne 0 ]; then
  echo "FAIL: our cadical_trace verdict DIVERGES from real upstream cadical on $n CNFs."
  echo "      A transcription bug is the likely cause -> fix cadical_trace.cc."
  exit 1
fi
echo "PASS: our cadical_trace agrees with real upstream cadical on all $n CNFs (verdict)."
