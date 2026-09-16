#!/usr/bin/env bash
# Build the instrumented MicroSAT C reference and print its decision trace for a CNF.
# Durable helper (kept in repo, never /tmp).
#   build_and_trace.sh <file.cnf>   trace one CNF
#   build_and_trace.sh --all        trace every CNF in cnf/ (golden refs for the shadow test)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
bin="$here/microsat-c/microsat_trace"
cc -O2 -o "$bin" "$here/microsat-c/microsat_trace.c"

if [ "${1:-}" = "--all" ]; then
  for cnf in "$here"/cnf/*.cnf; do
    echo "=== trace for $cnf ==="
    LSTRACE=1 "$bin" "$cnf" | grep -vE '^(s |c )' || true
  done
  exit 0
fi

cnf="${1:-$here/cnf/unsat_small.cnf}"
echo "=== trace for $cnf ==="
LSTRACE=1 "$bin" "$cnf"
