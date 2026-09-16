#!/usr/bin/env bash
# Regenerate the golden C kissat traces for the ASSUMPTION shadow test.
# Same idea as regen_golden_minisat_assume.sh: each shadow/cnf-assume/<name>.cnf has a
# matching <name>.assume (signed-DIMACS literals). The instrumented C kissat is run as
#     LSTRACE=1 kissat_trace <name>.cnf <name>.assume
# so the assumptions are forced as decisions before free search, exactly as the Kotlin
# Kissat.solve(assumptions). Golden traces go to shadow/golden-kissat-assume/.
#
# Run:  bash shadow/tools/regen_golden_kissat_assume.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
casedir="$shadow/cnf-assume"
golddir="$shadow/golden-kissat-assume"
csrc="$shadow/kissat-c/kissat_trace.cc"
cbin="$shadow/kissat-c/kissat_trace"

mkdir -p "$golddir"

echo "[1/2] building instrumented C kissat (c++ -O2 -std=c++17)"
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
