#!/usr/bin/env bash
# Stage the four standalone solver sub-repos under ~/github, from the current
# main-repo checkout. This is LOCAL only: it copies the solver source + gradle
# scaffolding + README/LICENSE into ~/github/<solver>-kotlin. No git, no remote.
#
# The nested `ksat-common` (mounted as common/) is copied in here as a plain dir so
# the repo builds standalone right away; when the real GitHub repos exist you replace
# common/ with a proper `git submodule add` (see doc/repo-split.md in the main repo).
#
# Re-runnable: wipes and re-stages each target's non-.git content.
set -euo pipefail

MAIN="$(cd "$(dirname "$0")/.." && pwd)"
GH="$HOME/github"
COMMON_SRC="$GH/ksat-common"   # staged ksat-common repo (must exist first)

if [ ! -d "$COMMON_SRC/src" ]; then
  echo "error: $COMMON_SRC not staged yet (run the ksat-common staging first)" >&2
  exit 1
fi

# solver key -> port .kt path (relative to main repo), Android namespace suffix,
# human name for the README, and whether it has a macosArm64 native benchmark.
solvers=("microsat" "minisat" "cadical" "kissat")

stage_one() {
  local key="$1"
  local repo="$GH/${key}-kotlin"
  local pkgdir="org/bytefred/ksat/${key}"
  local portfile
  portfile="$(cd "$MAIN" && ls "${key}"/src/commonMain/kotlin/${pkgdir}/*.kt)"

  echo "== staging ${key}-kotlin =="
  rm -rf "$repo/src" "$repo/common" "$repo/gradle" \
         "$repo/build.gradle.kts" "$repo/settings.gradle.kts" \
         "$repo/gradlew" "$repo/gradlew.bat" "$repo/gradle.properties" \
         "$repo/.gitignore" "$repo/README.md" "$repo/LICENSE"
  mkdir -p "$repo/src/commonMain/kotlin/${pkgdir}"

  # port source only (commonMain). Benchmarks + tests stay in the main repo.
  cp "$MAIN/$portfile" "$repo/src/commonMain/kotlin/${pkgdir}/"

  # gradle scaffolding for standalone builds
  mkdir -p "$repo/gradle/wrapper"
  cp "$MAIN/gradlew" "$MAIN/gradlew.bat" "$repo/"
  cp "$MAIN/gradle/wrapper/gradle-wrapper.jar" \
     "$MAIN/gradle/wrapper/gradle-wrapper.properties" "$repo/gradle/wrapper/"
  cp "$MAIN/gradle/libs.versions.toml" "$repo/gradle/"
  cp "$MAIN/gradle.properties" "$MAIN/.gitignore" "$repo/"

  # nested ksat-common as a plain copy for now (later: git submodule, mounted as common/)
  mkdir -p "$repo/common"
  cp -R "$COMMON_SRC/src" "$repo/common/"
  cp "$COMMON_SRC/build.gradle.kts" "$COMMON_SRC/settings.gradle.kts" "$repo/common/"

  echo "   staged $repo"
}

for k in "${solvers[@]}"; do
  stage_one "$k"
done

echo "done. build files (build.gradle.kts, settings.gradle.kts, README, LICENSE)"
echo "are written per-repo by the follow-up step."
