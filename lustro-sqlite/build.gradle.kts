plugins {
    // The Android library settings of every published module: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-sqlite is the SQLite tab: it reads the app's SQLite and Room databases
// under /api/v1/sqlite/. It depends only on :lustro-api; the app registers the tab
// with Lustro.builder(app).addTab(SqliteDebugTab.create(databases)). Its
// release-side mirror is :lustro-sqlite-noop.

android {
    namespace = "io.github.twinsen81.lustro.sqlite"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    api(project(":lustro-api"))
    // compileOnly: only an app that registers a SupportSQLiteDatabase, such as
    // Room's, runs the code that calls androidx.sqlite, and that app has it. The
    // floor, so that every call exists in each androidx.sqlite release.
    compileOnly(libs.sqlite.floor) { exclude(group = "org.jetbrains.kotlin") }

    testImplementation(project(":lustro"))
    // The route tests bind the real server, which listens while ProcessLifecycleOwner
    // says the app is in the foreground; they attach it to the test application.
    testImplementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    // A live SupportSQLiteDatabase, from the open helper that Room uses.
    testImplementation(libs.sqlite.framework)
}

lustroLibrary {
    // The same name in :lustro-sqlite-noop, so that an app can't resolve both.
    pairCapability("lustro-sqlite-runtime")
}
