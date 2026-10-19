plugins {
    // The same Android library settings as :lustro-logs: see
    // build-logic/.../AndroidLibraryPlugin.kt. detekt-baseline.xml here keeps the
    // unused parameter of the no-op factory.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-logs-noop is the release-safe half of :lustro-logs. It has the same public
// facade, so the app's release bootstrap compiles unchanged, but the tab reads no
// logs, serves nothing, and ships no assets.

android {
    namespace = "io.github.twinsen81.lustro.logs.noop"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lustro-api"))
}

lustroLibrary {
    // Mirrors :lustro-logs, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-logs-runtime")
}
