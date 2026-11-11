plugins {
    // The same Android library settings as :lustro: see
    // build-logic/.../AndroidLibraryPlugin.kt. detekt-baseline.xml here keeps the
    // unused parameters that every no-op body has.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-noop is the release-safe artifact. It exposes the SAME public facades
// as :lustro (so consumer code compiles unchanged) but every runtime body is a
// no-op. It depends on :lustro-api and OkHttp so it can mirror the OkHttp-typed
// facades (networkInterceptor(), OkHttpSender, NetworkDebugTab.create). The
// Gradle capability that makes :lustro and :lustro-noop mutually exclusive is
// declared in the lustroLibrary block below.

android {
    namespace = "io.github.twinsen81.lustro.noop"

    defaultConfig {
        // Lower than the runtime's: see noopMinSdk in the version catalog.
        minSdk = libs.versions.noopMinSdk.get().toInt()
        // R8 rules shipped to consumers (minimal: keep public facade entry points).
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lustro-api"))
    api(libs.okhttp)

    // The same lint.jar as :lustro ships. An app takes :lustro in debug only, so
    // without this copy the release lint has no check, and a DebugTab subclass in
    // src/release or src/main reaches the release APK unreported.
    lintPublish(project(":lustro-lint"))
}

lustroLibrary {
    // Mirrors :lustro, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-runtime")
}
