#!/usr/bin/env bash
# Prove the main-repo srcDir wiring compiles BEFORE the real submodules exist.
#
# It builds a throwaway probe project that reproduces exactly what a main-repo solver
# module will do: a thin build.gradle.kts whose commonMain srcDirs points at the solver
# port living in a sibling checkout (standing in for the submodule), depending on a single
# ksat-common. If this compiles, the "thin build file + srcDirs into submodule" wiring is
# sound and the duplicate-ksat-common trap is avoided.
#
# Uses microsat as the probe. Writes under build-probe/ (gitignored), keeps it for inspection.
set -euo pipefail
MAIN="$(cd "$(dirname "$0")/.." && pwd)"
GH="$HOME/github"
PROBE="$MAIN/build-probe/srcdir-wiring"

PORT_SRC="$GH/microsat-kotlin/src/commonMain/kotlin/org/bytefred/ksat/microsat"
COMMON_SRC="$GH/ksat-common/src/commonMain"
for d in "$PORT_SRC" "$COMMON_SRC"; do
  [ -d "$d" ] || { echo "error: missing $d (run staging first)" >&2; exit 1; }
done

rm -rf "$PROBE"; mkdir -p "$PROBE/gradle/wrapper" "$PROBE/microsat" "$PROBE/ksat-common"
cp "$MAIN/gradlew" "$MAIN/gradlew.bat" "$PROBE/"
cp "$MAIN/gradle/wrapper/gradle-wrapper.jar" "$MAIN/gradle/wrapper/gradle-wrapper.properties" "$PROBE/gradle/wrapper/"
cp "$MAIN/gradle/libs.versions.toml" "$PROBE/gradle/"
cp "$MAIN/gradle.properties" "$PROBE/"

cat > "$PROBE/settings.gradle.kts" <<EOF
rootProject.name = "srcdir-wiring-probe"
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
include(":ksat-common")
include(":microsat")
EOF

# top-level ksat-common (the ONE shared one), srcDirs into the real ksat-common checkout
cat > "$PROBE/ksat-common/build.gradle.kts" <<EOF
plugins { alias(libs.plugins.kotlinMultiplatform) }
kotlin {
    jvm()
    sourceSets {
        val commonMain by getting {
            kotlin.setSrcDirs(listOf("$COMMON_SRC/kotlin"))
        }
    }
}
EOF

# thin solver module: commonMain srcDirs pinned at the submodule port dir, depends on the ONE ksat-common
cat > "$PROBE/microsat/build.gradle.kts" <<EOF
plugins { alias(libs.plugins.kotlinMultiplatform) }
kotlin {
    jvm()
    sourceSets {
        val commonMain by getting {
            kotlin.setSrcDirs(listOf("$GH/microsat-kotlin/src/commonMain/kotlin"))
            dependencies { implementation(project(":ksat-common")) }
        }
    }
}
EOF

echo "== probe project written at $PROBE =="
cd "$PROBE"
timeout 180 ./gradlew :microsat:compileKotlinJvm --console=plain 2>&1 | tail -20
echo "EXIT: ${PIPESTATUS[0]}"
