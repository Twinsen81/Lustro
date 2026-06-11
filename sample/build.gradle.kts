import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin; no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.detekt)
}

// :sample is the public integration target, the cross-variant parity check, AND
// the idiomatic debug-only-usage demo:
//   - the debug variant consumes :lustro (real runtime), the release variant
//     consumes :lustro-noop; both must compile against the same public facades;
//   - ALL Lustro registration lives in the variant-split src/debug + src/release
//     LustroBootstrap, so the published LustroDebugUsageInRelease lint check is
//     clean WITHOUT being disabled (src/main mentions no Lustro type).

android {
    namespace = "io.github.twinsen81.lustro.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.twinsen81.lustro.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 exercises the no-op artifact + consumer rules on the release variant.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Note: there is NO `lint { disable += "LustroDebugUsageInRelease" }` here.
    // The sample now follows the recommended pattern — all Lustro registration
    // (Lustro.builder/.addTab and the SampleFlagsTab DebugTab subclass) lives in
    // the variant-split src/debug LustroBootstrap, so the published check finds
    // nothing to flag in src/main/src/release and :sample:lintDebug is clean.

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Detekt on the sample's Kotlin sources, using the shared library config. The
// UndocumentedPublic* rules are scoped in the config so they do not fire on this
// app's public surface (the sample is not a published library).
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin")
}

dependencies {
    // Debug builds get the real runtime; release builds get the no-op artifact.
    debugImplementation(project(":lustro"))
    releaseImplementation(project(":lustro-noop"))

    // CI network test (testDebugUnitTest): exercises the real interceptor against
    // an offline MockWebServer. The real :lustro runtime is already on the debug
    // unit-test classpath via debugImplementation; the test-only libs are added
    // here. Robolectric provides the Android stubs the runtime touches (Base64,
    // SharedPreferences) without an emulator.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
