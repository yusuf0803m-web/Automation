plugins {
    kotlin("jvm") version "2.0.21" apply false
}

tasks.register("verifyCore") {
    group = "verification"
    description = "Compiles and unit-tests the Android-independent automation core."
    dependsOn(":core:test")
}
