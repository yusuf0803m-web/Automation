// No `plugins` block here on purpose.
//
//  - Declaring the Android Gradle Plugin, even with `apply false`, resolves its
//    plugin marker, which breaks the Android-free `:core` build in environments
//    where Google's Maven repository is unreachable.
//  - Declaring the Kotlin plugin here puts kotlin-gradle-plugin on the shared
//    build classpath, which then collides with `:app` applying the Kotlin
//    Android plugin ("already on the classpath", then a failure to apply).
//
// Each module therefore declares the plugins it needs, with its own version.
// The versions must stay in step: Kotlin 2.0.21 in both :core and :app.

tasks.register("verifyCore") {
    group = "verification"
    description = "Compiles and unit-tests the Android-independent automation core."
    dependsOn(":core:test")
}
