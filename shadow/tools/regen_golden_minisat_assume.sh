#!/usr/bin/env bash
# Regenerate the golden C MiniSat traces for the ASSUMPTION shadow test.
#
# Same pipeline as regen_golden_minisat.sh, but each case in shadow/cnf-assume/ is a
# (<name>.cnf, <name>.assume) pair: the CNF plus a file of signed-DIMACS assumption
# literals. The instrumented C MiniSat is run as
#     LSTRACE=1 minisat_trace <name>.cnf <name>.assume
# so the assumptions are forced as decisions before free search, exactly as the Kotlin
# MiniSat.solve(assumptions). The pure event trace is saved to
# shadow/golden-minisat-assume/<name>.trace and diffed by the JVM assumption shadow test
# (minisat/.../ShadowAssumeTraceFilesTest.kt).
#
# Run:  bash shadow/tools/regen_golden_minisat_assume.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
casedir="$shadow/cnf-assume"
golddir="$shadow/golden-minisat-assume"
csrc="$shadow/minisat-c/minisat_trace.cc"
cbin="$shadow/minisat-c/minisat_trace"

mkdir -p "$golddir"

echo "[1/2] building instrumented C MiniSat (clang++ -O2)"
CXX="${CXX:-clang++}"
"$CXX" -O2 -std=c++11 -o "$cbin" "$csrc"

echo "[2/2] tracing every assumption case"
for cnf in "$casedir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  assume="$casedir/$name.assume"
  [ -f "$assume" ] || { echo "  WARN: no .assume for $name, skipping"; continue; }
  out="$golddir/$name.trace"
  LSTRACE=1 "$cbin" "$cnf" "$assume" | grep -vE '^(s |c )' > "$out" || true
  lines=$(wc -l < "$out" | tr -d ' ')
  verdict=$(grep -E '^RESULT ' "$out" | tail -1)
  printf '%-22s lines=%-5s %s\n' "$name" "$lines" "$verdict"
done

echo "done. golden assumption traces in $golddir"
