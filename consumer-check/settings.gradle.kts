// A small app on the oldest toolchain and dependency versions that Lustro
// supports, built against the Lustro artifacts in the local Maven repository.
// It is a separate Gradle build on purpose: the repository root uses the
// current toolchain, and this build has to stay on the floors. See README.md
// in this directory.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version providers.gradleProperty("agpVersion").get()
        id("org.jetbrains.kotlin.android") version providers.gradleProperty("kotlinVersion").get()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The Lustro artifacts come from `./gradlew publishToMavenLocal` in the
        // repository root. Everything else comes from the public repositories.
        mavenLocal {
            content { includeGroup("io.github.twinsen81") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "lustro-consumer-check"
include(":app")
