plugins {
    // The Android library settings of every published module: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-prefs is the Preferences tab: it reads and edits the app's
// SharedPreferences files and Preferences DataStores under /api/v1/prefs/. It
// depends only on :lustro-api; the app registers the tab with
// Lustro.builder(app).addTab(PrefsDebugTab.create(app)). Its release-side mirror
// is :lustro-prefs-noop.

android {
    namespace = "io.github.twinsen81.lustro.prefs"

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
    // compileOnly: only an app that registers a PrefsDataStore runs the code that
    // calls DataStore and coroutines, and that app has both. The floors, so that the
    // calls exist in every DataStore release. The exclusion keeps the old stdlib
    // that they name off the compile classpath, which checkKotlinFloor checks.
    compileOnly(libs.datastore.preferences.core.floor) { exclude(group = "org.jetbrains.kotlin") }
    compileOnly(libs.datastore.core.floor) { exclude(group = "org.jetbrains.kotlin") }
    compileOnly(libs.kotlinx.coroutines.core.floor) { exclude(group = "org.jetbrains.kotlin") }

    testImplementation(project(":lustro"))
    // The route tests bind the real server, which listens while ProcessLifecycleOwner
    // says the app is in the foreground; they attach it to the test application.
    testImplementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    // A newer DataStore than the compileOnly floor: 1.1 added byte array values.
    testImplementation(libs.datastore.preferences.core)
}

lustroLibrary {
    // The same name in :lustro-prefs-noop, so that an app can't resolve both.
    pairCapability("lustro-prefs-runtime")
}
