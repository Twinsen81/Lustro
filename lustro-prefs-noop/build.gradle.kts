plugins {
    // The same Android library settings as :lustro-prefs: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-prefs-noop is the release-safe half of :lustro-prefs. It has the same public
// facade, so the app's release bootstrap compiles unchanged, but the tab reads no
// files, serves nothing, and ships no assets.

android {
    namespace = "io.github.twinsen81.lustro.prefs.noop"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lustro-api"))
}

lustroLibrary {
    // Mirrors :lustro-prefs, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-prefs-runtime")
}
