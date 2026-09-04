#!/usr/bin/env bash
# Regenerate the golden C kissat traces for the JVM shadow test.
#
# Pipeline (durable, kept in repo):
#   1. generate the CNF suite                   (gen_cnf.py -> shadow/cnf/*.cnf)
#   2. build the instrumented C kissat core     (c++ -O2 -std=c++17 kissat_trace.cc)
#   3. for each CNF: run it with LSTRACE=1, strip the "s "/"c " status lines, and
#      save the pure event trace to shadow/golden-kissat/<name>.trace
#   4. record which CNFs contain RESTART / REDUCE lines into a manifest.
#
# The JVM shadow test (kissat/.../ShadowTraceFilesTest.kt) then reads
# shadow/cnf/*.cnf + shadow/golden-kissat/*.trace and compares the Kotlin trace at
# the strongest level that holds (L1 full trace, else L2 event/decision sequence,
# always L3 verdict+model).
#
# Env knobs for RESTART/REDUCE trace coverage (mirrored by the Kotlin ctor params):
#   LSREDUCEINIT=<n>  lower reduce interval so REDUCE fires on a small instance
#   LSSTABLEINIT=<n>  lower stabilize interval so a stable-mode restart fires
# Set them the SAME on both sides when producing a golden used for shadowing.
#
# Run:  bash shadow/tools/regen_golden_kissat.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
cnfdir="$shadow/cnf"
golddir="$shadow/golden-kissat"
csrc="$shadow/kissat-c/kissat_trace.cc"
cbin="$shadow/kissat-c/kissat_trace"

mkdir -p "$golddir"

echo "[1/4] generating CNF suite"
python3 "$here/gen_cnf.py"

echo "[2/4] building instrumented C kissat (c++ -O2 -std=c++17)"
CXX="${CXX:-c++}"
"$CXX" -O2 -std=c++17 -o "$cbin" "$csrc"

echo "[3/4] tracing every CNF"
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

echo "[4/4] done. golden kissat traces in $golddir"
echo
echo "CNFs that exercise RESTART/REDUCE (the paths we want covered):"
grep -E 'RESTART=yes|REDUCE=yes' "$manifest" || echo "  (none yet -- need a harder/larger instance in gen_cnf.py, or set LSREDUCEINIT/LSSTABLEINIT low and mirror in the Kotlin ctor)"
