plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// The facade module: a single `Ksat` entry point that picks one of the ported
// solvers at runtime (MiniSat / CaDiCaL / kissat), PySAT-style. Depends on every
// solver module, so it must NOT be depended on by them (ksat-common stays the base).
kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":ksat-common"))
                implementation(project(":minisat"))
                implementation(project(":cadical"))
                implementation(project(":kissat"))
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
