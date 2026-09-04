rootProject.name = "sat-solvers-kotlin"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":ksat-common")
include(":microsat")
include(":minisat")
include(":cadical")
include(":kissat")
include(":ksat")
