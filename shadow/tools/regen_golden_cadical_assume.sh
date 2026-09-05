#!/usr/bin/env bash
# Regenerate the golden C CaDiCaL traces for the ASSUMPTION shadow test.
# Each shadow/cnf-assume/<name>.cnf has a matching <name>.assume (signed-DIMACS literals).
# The instrumented C CaDiCaL core is run as
#     LSTRACE=1 cadical_trace <name>.cnf <name>.assume
# so the assumptions are forced as decisions before free search, exactly as the Kotlin
# CaDiCaL.solve(assumptions). Golden traces go to shadow/golden-cadical-assume/.
#
# Run:  bash shadow/tools/regen_golden_cadical_assume.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
casedir="$shadow/cnf-assume"
golddir="$shadow/golden-cadical-assume"
csrc="$shadow/cadical-c/cadical_trace.cc"
cbin="$shadow/cadical-c/cadical_trace"

mkdir -p "$golddir"

echo "[1/2] building instrumented C CaDiCaL (c++ -O2 -std=c++17)"
CXX="${CXX:-c++}"
"$CXX" -O2 -std=c++17 -o "$cbin" "$csrc"

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
