#!/usr/bin/env bash
# Independent oracle for the MicroSAT shadow (reviewer W4).
#
# The instrumented reference microsat_trace.c is a HAND-EDITED copy of upstream:
# we added trace() printfs. If our editing (or the earlier verbatim copy) silently
# changed the algorithm, the Kotlin port could match the trace and still be wrong --
# Kotlin == trace == wrong. Nothing so far checks trace.c against genuine upstream.
#
# This script closes that gap using the VERBATIM upstream copy that already lives in
# the repo: shadow/microsat-c/microsat_orig.c (copied unmodified out of other_repos;
# we do NOT touch other_repos here). It:
#   1. compiles microsat_orig.c  (verbatim upstream, no LSTRACE, no LSMAXLEMMAS)
#   2. compiles microsat_trace.c (our instrumented reference)
#   3. runs BOTH on every shadow/cnf/*.cnf and compares:
#        - the verdict line   `s SATISFIABLE` / `s UNSATISFIABLE`
#        - the conflict count from `c statistics ... conflicts: N ...`
#      Both files print these; the trace file is expected to be identical to
#      upstream apart from the LSTRACE-gated trace lines (which are off here).
#
# If the verdict or conflict count ever differs, our instrumented reference has
# drifted from upstream -> the trace goldens (and any Kotlin match against them)
# are suspect. Exit non-zero so CI / a pre-commit run notices.
#
# Run:  bash shadow/tools/validate_upstream_microsat.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
cdir="$shadow/microsat-c"
orig_src="$cdir/microsat_orig.c"
trace_src="$cdir/microsat_trace.c"
orig_bin="$cdir/microsat_orig"
trace_bin="$cdir/microsat_trace"

[ -f "$orig_src" ]  || { echo "FATAL: missing verbatim upstream $orig_src"; exit 2; }
[ -f "$trace_src" ] || { echo "FATAL: missing instrumented ref $trace_src"; exit 2; }

echo "[1/3] compiling verbatim upstream microsat_orig.c"
cc -O2 -o "$orig_bin" "$orig_src"
echo "[2/3] compiling instrumented microsat_trace.c"
cc -O2 -o "$trace_bin" "$trace_src"

# Extract "s SATISFIABLE"/"s UNSATISFIABLE" -> SAT/UNSAT
verdict() { grep -E '^s (SATISFIABLE|UNSATISFIABLE)$' | head -1 | awk '{print ($2=="SATISFIABLE")?"SAT":"UNSAT"}'; }
# Extract conflicts: N from the `c statistics` line
conflicts() { grep -E '^c statistics' | sed -E 's/.*conflicts: ([0-9]+).*/\1/' | head -1; }

echo "[3/3] comparing verdict + conflict count on every CNF"
fail=0; n=0
printf '%-26s %-6s %-6s %-8s %-8s %s\n' "instance" "orig" "trace" "conf_o" "conf_t" "status"
for cnf in "$cnfdir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  # trace binary WITHOUT LSTRACE so it prints only s/c lines (identical to orig)
  o_out="$("$orig_bin"  "$cnf")"
  t_out="$(env -u LSTRACE "$trace_bin" "$cnf")"
  o_v="$(printf '%s\n' "$o_out" | verdict)"
  t_v="$(printf '%s\n' "$t_out" | verdict)"
  o_c="$(printf '%s\n' "$o_out" | conflicts)"
  t_c="$(printf '%s\n' "$t_out" | conflicts)"
  status="ok"
  if [ "$o_v" != "$t_v" ] || [ "$o_c" != "$t_c" ]; then status="MISMATCH"; fail=1; fi
  printf '%-26s %-6s %-6s %-8s %-8s %s\n' "$name" "$o_v" "$t_v" "$o_c" "$t_c" "$status"
  n=$((n+1))
done

echo
if [ "$fail" -ne 0 ]; then
  echo "FAIL: instrumented microsat_trace.c DIVERGES from verbatim upstream on $n CNFs."
  echo "      Our reference (and its goldens) drifted from real MicroSAT -> fix trace.c."
  exit 1
fi
echo "PASS: instrumented reference agrees with verbatim upstream on all $n CNFs"
echo "      (verdict + conflict count). The trace goldens rest on genuine MicroSAT."
