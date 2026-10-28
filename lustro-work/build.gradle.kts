plugins {
    // The Android library settings of every published module: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-work is the WorkManager tab: the app's work, the events of each run, and
// cancel and prune, under /api/v1/work/. It depends only on :lustro-api; the app
// registers the tab with Lustro.builder(app).addTab(work) and passes its WorkManager
// Configuration.Builder through work.configure(builder). Its release-side mirror is
// :lustro-work-noop.

android {
    namespace = "io.github.twinsen81.lustro.work"

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

kotlin {
    compilerOptions {
        // The event listeners and their Configuration getters and setters are
        // @ExperimentalEventsApi, and WorkMetricsInfoRepository is also
        // @ExperimentalWorkMetricsApi; both markers are warnings. The opt-in stays in
        // this module: the public facade names no experimental type but the
        // repository, which only an app that uses work-analytics passes.
        optIn.add("androidx.work.ExperimentalEventsApi")
        optIn.add("androidx.work.analytics.ExperimentalWorkMetricsApi")
    }
}

dependencies {
    api(project(":lustro-api"))
    // compileOnly: the tab is for an app that has WorkManager, and the app's own
    // version runs. The floor, 2.12.0, is the first release with the calls the tab
    // makes. work-analytics must never be a runtime dependency: its POM pins
    // work-runtime to its own exact version. The exclusion keeps the newer stdlib
    // that both name off the compile classpath, which checkKotlinFloor checks.
    compileOnly(libs.work.runtime.floor) { exclude(group = "org.jetbrains.kotlin") }
    compileOnly(libs.work.analytics.floor) { exclude(group = "org.jetbrains.kotlin") }

    testImplementation(project(":lustro"))
    // The route tests bind the real server, which listens while ProcessLifecycleOwner
    // says the app is in the foreground; they attach it to the test application.
    testImplementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    // The newest WorkManager, not the compileOnly floor, with its test driver, so
    // that a change to the listener interfaces fails here before an app sees it.
    testImplementation(libs.work.testing)
    testImplementation(libs.work.analytics)
}

lustroLibrary {
    // The same name in :lustro-work-noop, so that an app can't resolve both.
    pairCapability("lustro-work-runtime")
}
