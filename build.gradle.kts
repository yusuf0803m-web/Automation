plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// The Android Gradle Plugin is deliberately NOT declared here: a root-level
// `plugins { ... apply false }` block still resolves the plugin marker, which
// would make the Android-free `:core` build fail when Google's Maven repository
// is unavailable. The :app module declares its own plugin versions instead.

tasks.register("verifyCore") {
    group = "verification"
    description = "Compiles and unit-tests the Android-independent automation core."
    dependsOn(":core:test")
}
