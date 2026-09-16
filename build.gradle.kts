plugins {
    // Applied per-module; declared here (apply false) so the version is shared and the Android
    // Gradle Plugin is on the shared buildscript classpath (so modules can apply com.android.library).
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidLibrary) apply false
}
