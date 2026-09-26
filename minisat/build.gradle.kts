plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// The Kotlin minisat port lives in the minisat/port submodule; only the port SOURCE is pulled in
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
    mingwX64()
    // Native macOS executable for the Kotlin/Native-vs-C runtime benchmark. Entry point:
    // minisat/src/macosArm64Main/.../Benchmark.kt (stays in the main repo).
    macosArm64 {
        binaries { executable { entryPoint = "org.bytefred.ksat.minisat.main" } }
    }

    sourceSets {
        val commonMain by getting {
            // port source comes from the submodule; pinned to the exact src dir so the
            // submodule's nested common/ is never swept in. NOTE: a srcDir is resolved
            // relative to THIS module's dir (minisat/), and the submodule is mounted at
            // minisat/port, so the module-relative path is "port/...", not "minisat/port/...".
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
    namespace = "org.bytefred.ksat.minisat"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

// The shadow trace test compares the largest benchmark instance php_10_9 byte-for-byte
// only when -Dbigtrace is set (its golden trace is up to 156 MB / 8.6M lines). Propagate
// that flag to the test JVM and give it an 8 GB heap so readLines() of that trace fits.
//   ./gradlew :minisat:jvmTest -Dbigtrace
tasks.withType<org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest>().configureEach {
    // Enabled by -Dbigtrace/-Pbigtrace. A bare flag (empty value) is ON; "false"/"0"/"no"/"off"
    // (or absent) leave it OFF.
    fun truthy(v: String?) = v != null && v.lowercase() !in setOf("false", "0", "no", "off")
    val bigtrace = truthy(providers.systemProperty("bigtrace").orNull) ||
        truthy(providers.gradleProperty("bigtrace").orNull)
    if (bigtrace) {
        systemProperty("bigtrace", "1")
        maxHeapSize = "8g"
    }
}

// Runtime benchmark on the shadow CNFs (JVM). Pair with shadow/tools to compare against C.
//   ./gradlew :minisat:runBenchmark
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Run the Kotlin minisat runtime benchmark on the shadow CNFs."
    dependsOn("jvmMainClasses")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("org.bytefred.ksat.minisat.Benchmark")
    workingDir = projectDir
}
