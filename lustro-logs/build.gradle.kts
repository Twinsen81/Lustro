plugins {
    // The Android library settings of every published module: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-logs is the Logs tab: it reads the app's own logcat and serves it under
// /api/v1/logs/. It depends only on :lustro-api; the app registers the tab with
// Lustro.builder(app).addTab(LogsDebugTab.create()). Its release-side mirror is
// :lustro-logs-noop.

android {
    namespace = "io.github.twinsen81.lustro.logs"

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

    testImplementation(project(":lustro"))
    // The route tests bind the real server, which listens while ProcessLifecycleOwner
    // says the app is in the foreground; they attach it to the test application.
    testImplementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
}

lustroLibrary {
    // The same name in :lustro-logs-noop, so that an app can't resolve both.
    pairCapability("lustro-logs-runtime")
}
