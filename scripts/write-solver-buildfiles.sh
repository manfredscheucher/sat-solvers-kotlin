#!/usr/bin/env bash
# Write the standalone build.gradle.kts + settings.gradle.kts for each solver sub-repo.
# Standalone means: compile the port against ksat-common, which is mounted at common/
# (a nested submodule). Tests/benchmarks/shadow are NOT here (they stay in the main repo),
# so these builds have commonMain only. Re-runnable.
set -euo pipefail
GH="$HOME/github"

# common settings block, shared by every solver repo. Binds :ksat-common -> common/.
settings_for() {
  local name="$1"
  cat > "$GH/${name}-kotlin/settings.gradle.kts" <<EOF
rootProject.name = "${name}-kotlin"

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

// ksat-common is a nested git submodule mounted at common/.
include(":ksat-common")
project(":ksat-common").projectDir = file("common")
EOF
}

# build.gradle.kts for the plain solvers (microsat, cadical, kissat).
build_plain() {
  local name="$1" nsSuffix="$2"
  cat > "$GH/${name}-kotlin/build.gradle.kts" <<EOF
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// Standalone build of the Kotlin ${name} port. Compiles the port against ksat-common
// (mounted at common/). The byte-for-byte shadow tests, benchmarks and the Ksat facade
// live in the main repo (sat-solvers-kotlin), which consumes this repo as a submodule.
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
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":ksat-common"))
            }
        }
    }
}

android {
    namespace = "org.bytefred.ksat.${nsSuffix}"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
EOF
}

# minisat additionally has a macosArm64 executable target for the native benchmark;
# but the benchmark entry point lives in the main repo, so standalone we only keep the
# library targets (no executable). Same plain build as the others.
settings_for microsat; build_plain microsat microsat
settings_for minisat;  build_plain minisat  minisat
settings_for cadical;  build_plain cadical  cadical
settings_for kissat;   build_plain kissat   kissat

echo "wrote build.gradle.kts + settings.gradle.kts for all four solver repos"
