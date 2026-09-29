#!/usr/bin/env bash
# Finish moving the shadow harness out to the optional ksat-extra submodule.
#
# Splits cleanly into a REMOTE step (Manfred, needs GitHub) and LOCAL steps (path
# rewrites + config), so it is re-runnable and documents the cutover. Run from the
# main repo root.
#
# PREREQUISITE (Manfred, once): ksat-extra pushed to GitHub, then
#   git rm -r shadow
#   git submodule add --name ksat-extra git@github.com:manfredscheucher/ksat-extra.git ksat-extra
# After that, this script does the local wiring (idempotent).
set -euo pipefail
MAIN="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MAIN"

# 1. Make ksat-extra an OPTIONAL submodule: a default `git clone --recursive` skips it.
if git config -f .gitmodules --get submodule.ksat-extra.path >/dev/null 2>&1; then
  git config -f .gitmodules submodule.ksat-extra.update none
  echo "set submodule.ksat-extra.update = none"
else
  echo "note: ksat-extra submodule not registered yet (run the prerequisite first)"
fi

# 2. Point the shadow-locating code at ksat-extra/shadow instead of shadow/.
#    7x  File(dir, "shadow")      (ShadowTrace*FilesTest.kt)
#    4x  File(dir, "shadow/cnf")  (jvmMain Benchmark.kt)
grep -rl 'File(dir, "shadow' solver --include='*.kt' | grep -v '/build/' | while read -r f; do
  sed -i '' -E 's#File\(dir, "shadow/cnf"\)#File(dir, "ksat-extra/shadow/cnf")#g; s#File\(dir, "shadow"\)#File(dir, "ksat-extra/shadow")#g' "$f"
  echo "rewrote paths in $f"
done

echo "done. Verify a shadow test actually RUNS (not skips):"
echo "  ./gradlew :minisat:jvmTest --offline   # test count > 0, no '-- skipping'"
