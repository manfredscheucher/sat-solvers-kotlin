#!/usr/bin/env bash
# Cut the MAIN repo over to consuming the solver ports from submodules.
#
# RUN THIS ONLY AFTER the five submodules are added (see scripts/README-repo-split.md):
#   common/            -> ksat-common          (the ONE shared ksat-common)
#   microsat/port/     -> microsat-kotlin       (etc. — the port checkout)
#   ... one per solver
#
# It does NOT touch git remotes. It:
#   1. deletes the main repo's own ksat-common module + each solver's commonMain port
#      (those now live in the submodules),
#   2. rewrites settings.gradle.kts to bind :ksat-common to common/ and keep the solvers,
#   3. rewrites each solver build.gradle.kts as a THIN file: commonMain srcDirs -> the
#      submodule port, tests/benchmark stay in the main repo, depend on the one ksat-common.
#
# Idempotent-ish: safe to re-run; it regenerates the build files and re-checks the deletions.
set -euo pipefail
MAIN="$(cd "$(dirname "$0")/.." && pwd)"
cd "$MAIN"

# Solvers to cut over. Each solver's port submodule is mounted at <solver>/port.
# (Plain list + convention instead of an associative array, so this runs on the
# macOS system bash 3.2, which has no `declare -A`.)
SOLVERS="microsat minisat cadical kissat"
COMMON_MOUNT="common"

# verify every expected mount point exists before touching anything
for m in "$COMMON_MOUNT"; do
  [ -d "$MAIN/$m" ] || { echo "error: submodule mount '$m' missing. Add the submodules first (README-repo-split.md)." >&2; exit 1; }
done
for k in $SOLVERS; do
  [ -d "$MAIN/$k/port" ] || { echo "error: submodule mount '$k/port' missing. Add the submodules first (README-repo-split.md)." >&2; exit 1; }
done

echo "== 1. drop main-repo copies now living in submodules =="
# the standalone ksat-common module in the main repo is replaced by the common/ submodule
rm -rf "$MAIN/ksat-common"
# each solver's commonMain port is replaced by the submodule port
for k in $SOLVERS; do
  rm -rf "$MAIN/$k/src/commonMain"
done

echo "== 2. rewrite settings.gradle.kts =="
cat > "$MAIN/settings.gradle.kts" <<EOF
rootProject.name = "sat-solvers-kotlin"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// ksat-common is the shared base, pulled in ONCE as the common/ submodule.
// The nested common/ inside each solver submodule is NOT included here.
include(":ksat-common")
project(":ksat-common").projectDir = file("$COMMON_MOUNT")

include(":microsat")
include(":minisat")
include(":cadical")
include(":kissat")
include(":ksat")
EOF

echo "== 3. thin per-solver build files (srcDirs into submodule port) =="
# args: key nsSuffix portMount extraTargets(macos|"")
thin_build() {
  local key="$1" ns="$2" mount="$3" macos="$4"
  local pkg="org/bytefred/ksat/${key}"
  local mainClass="org.bytefred.ksat.${key}.Benchmark"

  local macosBlock=""
  if [ "$macos" = "macos" ]; then
    macosBlock=$(cat <<MB

    // Native macOS executable for the Kotlin/Native-vs-C runtime benchmark. Entry point:
    // ${key}/src/macosArm64Main/.../Benchmark.kt (stays in the main repo).
    macosArm64 {
        binaries { executable { entryPoint = "org.bytefred.ksat.${key}.main" } }
    }
MB
)
  fi

  cat > "$MAIN/$key/build.gradle.kts" <<EOF
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// The Kotlin $key port lives in the $mount submodule; only the port SOURCE is pulled in
// here (via commonMain srcDirs). The shadow tests, sanity tests and the runtime benchmark
// stay in THIS repo (they need shadow/ and ksat-common). Depends on the one ksat-common.
kotlin {
    androidTarget()
    jvm()

    js { browser(); nodejs() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser(); nodejs() }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    linuxX64()
    mingwX64()$macosBlock

    sourceSets {
        val commonMain by getting {
            // port source comes from the submodule; pinned to the exact src dir so the
            // submodule's nested common/ is never swept in. NOTE: a srcDir is resolved
            // relative to THIS module's dir ($key/), and the submodule is mounted at
            // $key/port, so the module-relative path is "port/...", not "$mount/...".
            kotlin.srcDir("port/src/commonMain/kotlin")
            dependencies {
                implementation(project(":ksat-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(project(":ksat-common"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "org.bytefred.ksat.${ns}"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

// The shadow trace test compares the largest benchmark instance php_10_9 byte-for-byte
// only when -Dbigtrace is set (its golden trace is up to 156 MB / 8.6M lines). Propagate
// that flag to the test JVM and give it an 8 GB heap so readLines() of that trace fits.
//   ./gradlew :$key:jvmTest -Dbigtrace
tasks.withType<org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest>().configureEach {
    val bigtrace = providers.systemProperty("bigtrace").orNull != null ||
        providers.gradleProperty("bigtrace").orNull != null
    if (bigtrace) {
        systemProperty("bigtrace", "1")
        maxHeapSize = "8g"
    }
}

// Runtime benchmark on the shadow CNFs (JVM). Pair with shadow/tools to compare against C.
//   ./gradlew :$key:runBenchmark
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Run the Kotlin $key runtime benchmark on the shadow CNFs."
    dependsOn("jvmMainClasses")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("$mainClass")
    workingDir = projectDir
}
EOF
  echo "   wrote $key/build.gradle.kts (srcDir -> $mount)"
}

thin_build microsat microsat "microsat/port" ""
thin_build minisat  minisat  "minisat/port"  macos
thin_build cadical  cadical  "cadical/port"  ""
thin_build kissat   kissat   "kissat/port"   ""

echo
echo "cutover done. Now: ./gradlew jvmTest  (runs the shadow tests against the submodule ports)"
