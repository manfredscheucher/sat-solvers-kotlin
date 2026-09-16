plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()
    jvm()

    js { browser(); nodejs() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser(); nodejs() }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Native macOS target with an executable, for the Kotlin/Native-vs-C runtime benchmark
    // (a real native binary, no JVM/JIT). Entry point: macosMain/Benchmark.kt.
    macosArm64 {
        binaries {
            executable {
                entryPoint = "org.bytefred.ksat.minisat.main"
            }
        }
    }
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
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

// Runtime benchmark: solves the shadow CNFs with the Kotlin MiniSat and prints
// solve times. Pair with shadow/tools/benchmark.sh to compare against C MiniSat.
//   ./gradlew :minisat:runBenchmark
//   ./gradlew :minisat:runBenchmark --args="../shadow/cnf/php_7_6.cnf"
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Run the Kotlin MiniSat runtime benchmark on the shadow CNFs."
    dependsOn("jvmMainClasses")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("org.bytefred.ksat.minisat.Benchmark")
    workingDir = projectDir
}
