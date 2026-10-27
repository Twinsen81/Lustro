plugins {
    // The same Android library settings as :lustro-sqlite: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-sqlite-noop is the release-safe half of :lustro-sqlite. It has the same
// public facade, so the app's release bootstrap compiles unchanged, but the tab
// opens no database, serves nothing, and ships no assets.

android {
    namespace = "io.github.twinsen81.lustro.sqlite.noop"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":lustro-api"))
    // SqliteDb.support takes a SupportSQLiteDatabase supplier, as in :lustro-sqlite.
    // An app that registers one has androidx.sqlite; the no-op half never calls it.
    compileOnly(libs.sqlite.floor) { exclude(group = "org.jetbrains.kotlin") }
}

lustroLibrary {
    // Mirrors :lustro-sqlite, so a consumer cannot resolve both on the same configuration.
    pairCapability("lustro-sqlite-runtime")
}
