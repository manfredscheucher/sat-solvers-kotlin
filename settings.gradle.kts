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

// ksat-common is the shared base, pulled in ONCE as the ksat-common/ submodule.
// The nested common/ inside each solver submodule is NOT included here.
include(":ksat-common")
project(":ksat-common").projectDir = file("ksat-common")

// Each solver's thin wrapper module lives under solver/<name>/; its port source is the
// nested solver/<name>/<name>-kotlin submodule (pulled in via srcDir in the wrapper).
include(":microsat")
project(":microsat").projectDir = file("solver/microsat")
include(":minisat")
project(":minisat").projectDir = file("solver/minisat")
include(":cadical")
project(":cadical").projectDir = file("solver/cadical")
include(":kissat")
project(":kissat").projectDir = file("solver/kissat")
include(":ksat")
