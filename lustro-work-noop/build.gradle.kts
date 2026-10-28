plugins {
    // The same Android library settings as :lustro-work: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-work-noop is the release-safe half of :lustro-work. It has the same public
// facade, so the app's release bootstrap compiles unchanged, but configure()
// returns the builder as it is, the tab reads nothing, and it ships no assets.

android {
    namespace = "io.github.twinsen81.lustro.work.noop"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        // As in :lustro-work: the facade takes a WorkMetricsInfoRepository.
        optIn.add("androidx.work.ExperimentalEventsApi")
        optIn.add("androidx.work.analytics.ExperimentalWorkMetricsApi")
    }
}

dependencies {
    api(project(":lustro-api"))
    // configure() takes the app's Configuration.Builder, and create() its
    // WorkMetricsInfoRepository, as in :lustro-work. The app has WorkManager; the
    // no-op half calls nothing on either.
    compileOnly(libs.work.runtime.floor) { exclude(group = "org.jetbrains.kotlin") }
    compileOnly(libs.work.analytics.floor) { exclude(group = "org.jetbrains.kotlin") }
}

lustroLibrary {
    // Mirrors :lustro-work, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-work-runtime")
}
