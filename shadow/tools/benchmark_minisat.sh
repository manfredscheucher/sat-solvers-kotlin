#!/usr/bin/env bash
# Runtime benchmark: Kotlin MiniSat port vs the C MiniSat reference vs the Kotlin
# MicroSat port, on the same shadow CNFs.
#
# What it does (durable, kept in repo):
#   1. generate the CNF suite                    (gen_cnf.py)
#   2. build the C MiniSat reference             (clang++ -O2 minisat_trace.cc)
#   3. time the C binary on each CNF             (median of N runs, whole process)
#   4. run the Kotlin MiniSat benchmark          (:minisat:runBenchmark, solve-only)
#   5. run the Kotlin MicroSat benchmark         (:microsat:runBenchmark, solve-only)
#   6. print a side-by-side table
#
# Note on what's timed: C number = wall time of the whole process (parse+solve+exit);
# Kotlin numbers = solve() only, median over warmed-up runs. For these instances
# parse/IO is negligible vs solve, so they are order-of-magnitude comparable.
#
# Run:  bash shadow/tools/benchmark_minisat.sh
# Env:  RUNS=<n> (default 5); CXX=<compiler> (default clang++)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
root="$(cd "$shadow/.." && pwd)"
cnfdir="$shadow/cnf"
csrc="$shadow/minisat-c/minisat_trace.cc"
cbin="$shadow/minisat-c/minisat_bench"
RUNS="${RUNS:-5}"
CXX="${CXX:-clang++}"

echo "[1/6] generating CNF suite"
python3 "$here/gen_cnf.py" >/dev/null

echo "[2/6] building C MiniSat (clang++ -O2, no LSTRACE)"
"$CXX" -O2 -std=c++11 -o "$cbin" "$csrc"

time_c () {
  local cnf="$1"; local -a ms=()
  local i start end
  for ((i = 0; i < RUNS; i++)); do
    start=${EPOCHREALTIME//[.,]/}
    "$cbin" "$cnf" >/dev/null 2>&1 || true
    end=${EPOCHREALTIME//[.,]/}
    ms+=( $(awk -v s="$start" -v e="$end" 'BEGIN{printf "%.3f", (e-s)/1000.0}') )
  done
  printf '%s\n' "${ms[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'
}

echo "[3/6] timing C MiniSat on each CNF (median of $RUNS runs)"
declare -a names=() ctimes=()
for cnf in "$cnfdir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  t=$(time_c "$cnf")
  names+=("$name"); ctimes+=("$t")
  printf '  %-26s C=%8.3f ms\n' "$name" "$t"
done

gradle_cmd="$root/gradlew"
[ -x "$gradle_cmd" ] || gradle_cmd="gradle"

echo "[4/6] running Kotlin MiniSat benchmark via Gradle"
mini_out="$shadow/golden-minisat/kotlin_minisat_bench.txt"
mkdir -p "$shadow/golden-minisat"
( cd "$root" && "$gradle_cmd" :minisat:runBenchmark -q ) | tee "$mini_out"

echo "[5/6] running Kotlin MicroSat benchmark via Gradle"
micro_out="$shadow/golden-minisat/kotlin_microsat_bench.txt"
( cd "$root" && "$gradle_cmd" :microsat:runBenchmark -q ) | tee "$micro_out"

echo
echo "[6/6] side-by-side (all median ms; C = whole process, Kotlin = solve-only)"
printf '%-26s %12s %14s %14s\n' "cnf" "C_MiniSat" "Kt_MiniSat" "Kt_MicroSat"
for i in "${!names[@]}"; do
  n="${names[$i]}"
  mini=$(awk -v k="$n" '$1==k{print $3}' "$mini_out"); [ -z "$mini" ] && mini="-"
  micro=$(awk -v k="$n" '$1==k{print $3}' "$micro_out"); [ -z "$micro" ] && micro="-"
  printf '%-26s %12s %14s %14s\n' "$n" "${ctimes[$i]}" "$mini" "$micro"
done
echo
echo "Note: numbers above are MEASURED on this machine when you run this script."
