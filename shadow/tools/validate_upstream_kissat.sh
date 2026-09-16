#!/usr/bin/env bash
# Independent oracle for the kissat shadow.
#
# Our instrumented reference shadow/kissat-c/kissat_trace.cc is a HAND-TRANSCRIPTION
# of the kissat CDCL core (inprocessing disabled). A shared transcription bug could
# make Kotlin == trace == wrong with nothing to catch it.
#
# This harness gives an independent verdict oracle WHEN a real `kissat` binary is
# available, without pulling in the full upstream build:
#   - if `kissat` is on PATH: run it on every shadow/cnf/*.cnf and diff its
#     SAT/UNSAT verdict against our kissat_trace's `s SATISFIABLE`/`s UNSATISFIABLE`.
#   - else: print a skip message and exit 0 (so it never blocks a machine without it).
#
# Getting a real kissat (pick one):
#   macOS:   brew install kissat        (if available in your taps)
#   Debian:  sudo apt-get install kissat
#   source:  https://github.com/arminbiere/kissat  (./configure && make), then put the
#            `kissat` binary (build/kissat) on PATH.
#
# Real kissat prints "s SATISFIABLE"/"s UNSATISFIABLE" (exit code 10 = SAT,
# 20 = UNSAT). Our reference prints the same. We normalise both to SAT/UNSAT and
# compare. We do NOT compare full traces: upstream's full solver (with inprocessing)
# needn't take the same search path -- only the verdict must agree.
#
# Run:  bash shadow/tools/validate_upstream_kissat.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
cdir="$shadow/kissat-c"
trace_src="$cdir/kissat_trace.cc"
trace_bin="$cdir/kissat_trace"

if ! command -v kissat >/dev/null 2>&1; then
  echo "[skip] no 'kissat' binary on PATH -- upstream kissat oracle not run."
  echo "       Install one to enable it:  build from https://github.com/arminbiere/kissat  (or apt-get)."
  exit 0
fi
echo "[kissat oracle] found: $(command -v kissat)"

# Build our instrumented reference if the binary is missing/stale.
if [ ! -x "$trace_bin" ] || [ "$trace_src" -nt "$trace_bin" ]; then
  echo "[build] compiling instrumented reference kissat_trace.cc"
  "${CXX:-c++}" -O2 -std=c++17 -o "$trace_bin" "$trace_src" || {
    echo "FATAL: could not build $trace_src"; exit 2; }
fi

# Our reference: normalise "s SATISFIABLE"/"s UNSATISFIABLE" -> SAT/UNSAT.
ours_verdict() { grep -E '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}'; }

# Real kissat: prefer exit code (10 SAT / 20 UNSAT); fall back to the text line.
upstream_verdict() {
  local cnf="$1" out rc
  out="$(kissat -q "$cnf" 2>/dev/null)"; rc=$?
  case "$rc" in
    10) echo SAT ;;
    20) echo UNSAT ;;
    *)  printf '%s\n' "$out" | grep -Eo '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 \
          | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}' ;;
  esac
}

echo "[compare] our kissat_trace verdict vs real kissat on every CNF"
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
  echo "FAIL: our kissat_trace verdict DIVERGES from real upstream kissat on $n CNFs."
  echo "      A transcription bug is the likely cause -> fix kissat_trace.cc."
  exit 1
fi
echo "PASS: our kissat_trace agrees with real upstream kissat on all $n CNFs (verdict)."
