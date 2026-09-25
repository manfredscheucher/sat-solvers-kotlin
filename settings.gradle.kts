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
project(":ksat-common").projectDir = file("common")

include(":microsat")
include(":minisat")
include(":cadical")
include(":kissat")
include(":ksat")
