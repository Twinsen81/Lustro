plugins {
    // The same Android library settings as :lustro-screen: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-screen-noop is the release-safe half of :lustro-screen. It has the same
// public facade, so the app's release bootstrap compiles unchanged, but the tab
// reads no window, never calls the app's ScreenContextProvider, and ships no assets.

android {
    namespace = "io.github.twinsen81.lustro.screen.noop"

    defaultConfig {
        // Lower than the runtime's: see noopMinSdk in the version catalog.
        minSdk = libs.versions.noopMinSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lustro-api"))
}

lustroLibrary {
    // Mirrors :lustro-screen, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-screen-runtime")
}
