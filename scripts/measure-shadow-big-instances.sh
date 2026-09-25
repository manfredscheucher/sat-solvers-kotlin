#!/usr/bin/env bash
# Measure wall-clock time per solver for the shadow trace tests WITH the large benchmark
# instances (php_9_8, php_10_9) included in the byte-for-byte comparison, and find the
# minimum test-fork heap each solver needs (they read the whole golden trace via readLines).
#
# BENCHMARK_ONLY must be empty in the four ShadowTraceFilesTest.kt for this run.
# The heap that matters is the TEST FORK's -> injected via maxHeapSize in an init script.
# configuration-cache is disabled so the init script reliably applies.
cd "$(cd "$(dirname "$0")/.." && pwd)"

HEAP="${HEAP:-8g}"
OUT="build-probe/shadow-bigmeasure"
mkdir -p "$OUT"

INIT="$OUT/heap-init.gradle.kts"
cat > "$INIT" <<'EOF'
allprojects {
    tasks.withType<Test>().configureEach {
        maxHeapSize = "__HEAP__"
        doFirst { println("[[heap]] " + name + " = " + maxHeapSize) }
    }
}
EOF
perl -pi -e "s/__HEAP__/$HEAP/" "$INIT"

echo "test-fork heap=$HEAP"
printf "%-10s %8s %9s %5s\n" "solver" "wall" "build_ok" "oom"
for s in microsat minisat cadical kissat; do
  log="$OUT/$s.log"
  ./gradlew ":$s:jvmTest" --rerun-tasks --no-configuration-cache --console=plain \
      --init-script "$INIT" > "$log" 2>&1
  wall=$(grep -oE "BUILD (SUCCESSFUL|FAILED) in [0-9ms ]+" "$log" | tail -1 | sed -E 's/.* in //')
  ok=$(grep -c "BUILD SUCCESSFUL" "$log"); ok=${ok//[!0-9]/}
  oom=$(grep -c "OutOfMemoryError" "$log"); oom=${oom//[!0-9]/}
  printf "%-10s %8s %9s %5s\n" "$s" "${wall:-?}" "$ok" "$oom"
done
echo "logs under $OUT/"
