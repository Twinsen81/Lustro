plugins {
    // The Android library settings of every published module: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
}

// :lustro-screen is the Screen tab: the app's windows and the Compose semantics
// and View trees in them, under /api/v1/screen/. It depends only on :lustro-api;
// the app registers the tab with Lustro.builder(app).addTab(screen). Its
// release-side mirror is :lustro-screen-noop.

android {
    namespace = "io.github.twinsen81.lustro.screen"

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
    // compileOnly: the tab reads the semantics tree of an app that has Compose, with
    // the app's own Compose UI, and checks at runtime that its classes are there; in
    // an app without Compose it walks the View tree only. The floor, 1.6.0, has every
    // call that the tab makes. The exclusion keeps the stdlib that Compose names off
    // the compile classpath, which checkKotlinFloor checks.
    compileOnly(libs.compose.ui.floor) { exclude(group = "org.jetbrains.kotlin") }

    testImplementation(project(":lustro"))
    // The server test binds the real server, which listens while ProcessLifecycleOwner
    // says the app is in the foreground; it attaches it to the test application.
    testImplementation(libs.androidx.lifecycle.process)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)
    testImplementation(libs.okhttp)
    testImplementation(libs.androidx.test.core)
    // The newest Compose of the catalog, not the compileOnly floor, so that a change
    // to the semantics API fails here before an app sees it.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui)
    testImplementation(libs.compose.foundation)
    testImplementation(libs.activity.compose)
}

// The unit tests compose the screens that the tab reads, so their compilations run
// the Compose compiler. The module's own compilation does not: the plugin would add a
// public $stable field to each published class, and the module has no composable.
val composeCompiler: Configuration by configurations.creating
dependencies {
    composeCompiler("org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:${libs.versions.kotlin.get()}")
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin")) pluginClasspath.from(composeCompiler)
}

// -PlustroScreenGoldenOut=<dir> writes what the golden test reads into <dir>, to
// update the fixtures after a change to the tree: compare, then copy them.
tasks.withType<Test>().configureEach {
    providers.gradleProperty("lustroScreenGoldenOut").orNull?.let { systemProperty("lustro.screen.golden.out", it) }
}

lustroLibrary {
    // The same name in :lustro-screen-noop, so that an app can't resolve both.
    pairCapability("lustro-screen-runtime")
}
