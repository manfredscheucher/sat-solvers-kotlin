#!/usr/bin/env bash
# Independent oracle for the MiniSat shadow (reviewer W4).
#
# Our instrumented reference shadow/minisat-c/minisat_trace.cc is a HAND-TRANSCRIPTION
# of upstream MiniSat (we don't build the real upstream in this repo -- it needs
# mtl/zlib and its own build system). A shared transcription bug could make
# Kotlin == trace == wrong with nothing to catch it.
#
# This harness gives an independent verdict oracle WHEN a real `minisat` binary is
# available, without forcing the heavy upstream build:
#   - if `minisat` is on PATH: run it on every shadow/cnf/*.cnf and diff its
#     SAT/UNSAT verdict against our minisat_trace's `s SATISFIABLE`/`s UNSATISFIABLE`.
#   - else: print a skip message and exit 0 (so it never blocks a machine without it).
#
# Getting a real minisat (pick one):
#   macOS:   brew install minisat
#   Debian:  sudo apt-get install minisat
#   source:  https://github.com/niklasso/minisat  (cmake .. && make), then put the
#            `minisat` binary on PATH.
#
# Real upstream minisat prints "SATISFIABLE"/"UNSATISFIABLE" (exit code 10 = SAT,
# 20 = UNSAT). Our reference prints "s SATISFIABLE"/"s UNSATISFIABLE". We normalise
# both to SAT/UNSAT and compare. We do NOT compare full traces: upstream has no
# LSTRACE, and its search order needn't match ours -- only the verdict must.
#
# Run:  bash shadow/tools/validate_upstream_minisat.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
cdir="$shadow/minisat-c"
trace_src="$cdir/minisat_trace.cc"
trace_bin="$cdir/minisat_trace"

if ! command -v minisat >/dev/null 2>&1; then
  echo "[skip] no 'minisat' binary on PATH -- upstream MiniSat oracle not run."
  echo "       Install one to enable it:  brew install minisat  (or apt-get, or build from source)."
  echo "       See the header of this script and validate_upstream_minisat.md."
  exit 0
fi
echo "[minisat oracle] found: $(command -v minisat)"

# Build our instrumented reference if the binary is missing/stale.
if [ ! -x "$trace_bin" ] || [ "$trace_src" -nt "$trace_bin" ]; then
  echo "[build] compiling instrumented reference minisat_trace.cc"
  c++ -O2 -std=c++11 -o "$trace_bin" "$trace_src" || {
    echo "FATAL: could not build $trace_src"; exit 2; }
fi

# Our reference: normalise "s SATISFIABLE"/"s UNSATISFIABLE" -> SAT/UNSAT.
ours_verdict() { grep -E '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}'; }

# Real minisat: prefer exit code (10 SAT / 20 UNSAT); fall back to the text line.
upstream_verdict() {
  local cnf="$1" out rc
  out="$(minisat "$cnf" /dev/null 2>/dev/null)"; rc=$?
  case "$rc" in
    10) echo SAT ;;
    20) echo UNSAT ;;
    *)  printf '%s\n' "$out" | grep -Eo '^(SATISFIABLE|UNSATISFIABLE)$' | head -1 \
          | sed 's/^SATISFIABLE$/SAT/; s/^UNSATISFIABLE$/UNSAT/' ;;
  esac
}

echo "[compare] our minisat_trace verdict vs real minisat on every CNF"
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
  echo "FAIL: our minisat_trace verdict DIVERGES from real upstream minisat on $n CNFs."
  echo "      A transcription bug is the likely cause -> fix minisat_trace.cc."
  exit 1
fi
echo "PASS: our minisat_trace agrees with real upstream minisat on all $n CNFs (verdict)."
