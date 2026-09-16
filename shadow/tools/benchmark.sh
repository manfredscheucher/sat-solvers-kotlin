#!/usr/bin/env bash
# Runtime benchmark: Kotlin MicroSat port vs the C original, same CNFs.
#
# What it does (durable, kept in repo):
#   1. generate the CNF suite                 (gen_cnf.py)
#   2. build the C original with cc -O2        (microsat_orig.c -> microsat_orig)
#   3. time the C binary on each CNF           (median of N runs, whole process)
#   4. run the Kotlin benchmark via Gradle     (:microsat:runBenchmark, solve-only)
#   5. print a side-by-side table
#
# Note on what's timed:
#   - C number = wall time of the whole `microsat_orig <cnf>` process (parse+solve+exit).
#   - Kotlin number = solve() only, median over warmed-up runs (JVM JIT warm-up
#     excluded on purpose). The two are not identical measurements, but for these
#     instances parse/IO is negligible vs solve, so they are comparable. The point
#     is order-of-magnitude: the port should be within a small constant factor of C.
#
# Run:  bash shadow/tools/benchmark.sh
# Env:  RUNS=<n> (default 5) number of timed C runs per CNF.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
shadow="$(cd "$here/.." && pwd)"
root="$(cd "$shadow/.." && pwd)"
cnfdir="$shadow/cnf"
csrc="$shadow/microsat-c/microsat_orig.c"
cbin="$shadow/microsat-c/microsat_orig"
RUNS="${RUNS:-5}"

echo "[1/5] generating CNF suite"
python3 "$here/gen_cnf.py" >/dev/null

echo "[2/5] building C original (cc -O2)"
cc -O2 -o "$cbin" "$csrc"

# median wall time in milliseconds of running $cbin on one cnf, over $RUNS runs.
# Uses bash 5's EPOCHREALTIME (microsecond wall clock, no subprocess overhead).
time_c () {
  local cnf="$1"; local -a ms=()
  local i start end
  for ((i = 0; i < RUNS; i++)); do
    # EPOCHREALTIME may use ',' or '.' as the decimal sep depending on locale;
    # strip BOTH so we get pure integer microseconds since epoch.
    start=${EPOCHREALTIME//[.,]/}
    "$cbin" "$cnf" >/dev/null 2>&1 || true
    end=${EPOCHREALTIME//[.,]/}
    ms+=( $(awk -v s="$start" -v e="$end" 'BEGIN{printf "%.3f", (e-s)/1000.0}') )
  done
  printf '%s\n' "${ms[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}'
}

echo "[3/5] timing C original on each CNF (median of $RUNS runs)"
declare -a names=() ctimes=()
for cnf in "$cnfdir"/*.cnf; do
  name="$(basename "$cnf" .cnf)"
  t=$(time_c "$cnf")
  names+=("$name"); ctimes+=("$t")
  printf '  %-26s C=%8.3f ms\n' "$name" "$t"
done

echo "[4/5] running Kotlin benchmark via Gradle"
gradle_cmd="$root/gradlew"
[ -x "$gradle_cmd" ] || gradle_cmd="gradle"
# Capture the Kotlin benchmark output (solve-only median ms per CNF).
kt_out="$shadow/golden/kotlin_bench.txt"
mkdir -p "$shadow/golden"
( cd "$root" && "$gradle_cmd" :microsat:runBenchmark -q ) | tee "$kt_out"

echo
echo "[5/5] side-by-side (C = whole process, Kotlin = solve-only, both median ms)"
printf '%-26s %12s %14s\n' "cnf" "C_ms" "Kotlin_ms"
for i in "${!names[@]}"; do
  n="${names[$i]}"
  # The Kotlin benchmark prints the cnf name WITH the .cnf suffix; match either form.
  ktms=$(awk -v k="$n" -v kc="$n.cnf" '$1==k || $1==kc {print $3}' "$kt_out")
  [ -z "$ktms" ] && ktms="-"
  printf '%-26s %12s %14s\n' "$n" "${ctimes[$i]}" "$ktms"
done
echo
echo "Note: numbers above are MEASURED on this machine when you run this script."
echo "Kotlin will be slower (JVM warm-up, bounds checks) but should be a small"
echo "constant factor, not 100x. If any Kotlin run OOMs, bump MEM_MAX in MicroSat.kt."
