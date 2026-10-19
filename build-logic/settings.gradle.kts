// The convention plugins of the Lustro build. The root settings include this
// build with `pluginManagement { includeBuild("build-logic") }`, and the modules
// apply the plugins by id: see build.gradle.kts here for the list.

dependencyResolutionManagement {
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
    // The same catalog as the main build, so both name the same plugin versions.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
