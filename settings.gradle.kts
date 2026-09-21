// ---------------------------------------------------------------------------
// Pelita Auto Continue
//
// The project is split in two:
//
//   :core  pure Kotlin/JVM. State machine, generation detector, debouncer,
//          duplicate prevention, activity log. No Android dependency at all,
//          so it compiles and unit-tests on any JDK without the Android SDK.
//
//   :app   the Android application (Compose UI + AccessibilityService). It is
//          only included when an Android SDK is actually available, because the
//          Android Gradle Plugin and AndroidX live on Google's Maven repository.
//          Provide a local.properties with `sdk.dir=...`, or export ANDROID_HOME,
//          or run with -PincludeApp=true.
//
// The `withAndroid` expression is duplicated on purpose: `pluginManagement` is
// evaluated before the rest of this script, so it cannot call a function
// declared further down.
// ---------------------------------------------------------------------------

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Only declared when it can actually be reached; an unreachable
        // repository makes every plugin resolution fail, not just AGP's.
        if (settings.startParameter.projectProperties["includeApp"] == "true" ||
            System.getenv("ANDROID_HOME") != null ||
            System.getenv("ANDROID_SDK_ROOT") != null ||
            java.io.File(settings.settingsDir, "local.properties").exists()
        ) {
            google()
        }
    }
}

val withAndroid: Boolean =
    settings.startParameter.projectProperties["includeApp"] == "true" ||
        System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        java.io.File(settings.settingsDir, "local.properties").exists()

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        if (withAndroid) {
            google()
        }
    }
}

rootProject.name = "pelita-auto-continue"

include(":core")

if (withAndroid) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "[pelita] Android SDK not detected - only :core is included. " +
                "Run with -PincludeApp=true or provide local.properties/ANDROID_HOME to build the APK."
        )
    }
}
