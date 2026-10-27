pluginManagement {
    // The convention plugins (lustro.android-library, lustro.kotlin-library,
    // lustro.publishing, lustro.facade-parity). See build-logic/build.gradle.kts.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lustro"

include(":lustro-api")
include(":lustro")
include(":lustro-noop")
include(":lustro-logs")
include(":lustro-logs-noop")
include(":lustro-prefs")
include(":lustro-prefs-noop")
include(":lustro-sqlite")
include(":lustro-sqlite-noop")
include(":lustro-lint")
include(":lustro-wire-schema")
include(":sample")

// Note: the CLI lives in lustro-cli/ as a Python package, not a Gradle module.
