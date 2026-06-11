pluginManagement {
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
include(":lustro-lint")
include(":lustro-wire-schema")
include(":sample")

// Note: the CLI lives in lustro-cli/ as a Python package, not a Gradle module.
