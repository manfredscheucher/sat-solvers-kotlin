plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

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

// Runtime benchmark: solves the shadow CNFs with the Kotlin MicroSat and prints
// solve times. Pair with shadow/tools/benchmark.sh to compare against C.
//   ./gradlew :microsat:runBenchmark
//   ./gradlew :microsat:runBenchmark --args="../shadow/cnf/php_7_6.cnf"
tasks.register<JavaExec>("runBenchmark") {
    group = "benchmark"
    description = "Run the Kotlin MicroSat runtime benchmark on the shadow CNFs."
    dependsOn("jvmMainClasses")
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("org.bytefred.ksat.microsat.Benchmark")
    // run from the module dir so the CNF auto-locate walks up to shadow/cnf
    workingDir = projectDir
}
