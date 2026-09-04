#!/usr/bin/env bash
# Regenerate the golden C traces for the JVM shadow test.
#
# Pipeline (durable, kept in repo):
#   1. generate the CNF suite               (gen_cnf.py -> shadow/cnf/*.cnf)
#   2. build the instrumented C reference   (cc -O2 microsat_trace.c)
#   3. for each CNF: run it with LSTRACE=1, strip the "s "/"c " status/comment
#      lines, and save the pure event trace to shadow/golden/<name>.trace
#   4. record which CNFs actually contain RESTART / REDUCE lines (the ones that
#      exercise those hand-translated paths) into shadow/golden/manifest.txt
#   5. re-trace a couple of UNSAT instances with a tiny LSMAXLEMMAS so the REDUCE
#      path (reduceDB) is actually exercised -> <name>_lemmas<k>.trace
#
# The JVM shadow test (ShadowTraceFilesTest.kt) then reads shadow/cnf/*.cnf +
# shadow/golden/*.trace and compares the Kotlin trace line-for-line.
#
# Run:  bash shadow/tools/regen_golden.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
golddir="$shadow/golden"
csrc="$shadow/microsat-c/microsat_trace.c"
cbin="$shadow/microsat-c/microsat_trace"

mkdir -p "$golddir"

echo "[1/5] generating CNF suite"
python3 "$here/gen_cnf.py"

echo "[2/5] building instrumented C reference (cc -O2)"
cc -O2 -o "$cbin" "$csrc"

echo "[3/5] tracing every CNF"
manifest="$golddir/manifest.txt"
: > "$manifest"
for cnf in "$cnfdir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  out="$golddir/$name.trace"
  LSTRACE=1 "$cbin" "$cnf" | grep -vE '^(s |c )' > "$out" || true
  restart=no; reduce=no
  if grep -q '^RESTART$' "$out"; then restart=yes; fi
  if grep -q '^REDUCE$'  "$out"; then reduce=yes; fi
  lines=$(wc -l < "$out" | tr -d ' ')
  printf '%-26s lines=%-6s RESTART=%s REDUCE=%s\n' "$name" "$lines" "$restart" "$reduce" | tee -a "$manifest"
done

echo "[4/5] tracing low-maxLemmas variants (force REDUCE/RESTART via LSMAXLEMMAS)"
# MicroSAT's default maxLemmas=2000 is too conservative for any fast instance to
# reach the REDUCE path. To trace-cover reduceDB (the dual-location watch-pointer
# compaction) we re-trace a couple of existing UNSAT instances with a tiny
# maxLemmas via the LSMAXLEMMAS knob. The Kotlin port mirrors this exactly with
# its maxLemmasInit constructor param, so the traces must still match byte-for-byte.
# These goldens are consumed by ShadowTraceFilesTest.lowMaxLemmasForcesReduce*.
for spec in "php_5_4:3" "php_6_5:3"; do
  name="${spec%%:*}"; lemmas="${spec##*:}"
  cnf="$cnfdir/$name.cnf"
  [ -f "$cnf" ] || { echo "  skip $name (no cnf)"; continue; }
  out="$golddir/${name}_lemmas${lemmas}.trace"
  LSMAXLEMMAS="$lemmas" LSTRACE=1 "$cbin" "$cnf" | grep -vE '^(s |c )' > "$out" || true
  restart=no; reduce=no
  if grep -q '^RESTART$' "$out"; then restart=yes; fi
  if grep -q '^REDUCE$'  "$out"; then reduce=yes; fi
  lines=$(wc -l < "$out" | tr -d ' ')
  printf '%-26s lines=%-6s RESTART=%s REDUCE=%s LSMAXLEMMAS=%s\n' "${name}_lemmas${lemmas}" "$lines" "$restart" "$reduce" "$lemmas" | tee -a "$manifest"
done

echo "[5/5] done. golden traces in $golddir"
echo
echo "CNFs that exercise RESTART/REDUCE (the paths we want covered):"
grep -E 'RESTART=yes|REDUCE=yes' "$manifest" || echo "  (none yet -- need a harder instance or a lower LSMAXLEMMAS)"
