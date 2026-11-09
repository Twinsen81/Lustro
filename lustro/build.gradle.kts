import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    // compileSdk, minSdk, the "lustro_" resource prefix, no BuildConfig, toolchain
    // 21 with JVM 17 bytecode, explicit API, the Kotlin floor, and detekt: see
    // build-logic/.../AndroidLibraryPlugin.kt.
    id("lustro.android-library")
    // The release AAR, a sources jar, and a Dokka Javadoc jar; POM_NAME and
    // POM_DESCRIPTION come from gradle.properties here.
    id("lustro.publishing")
    alias(libs.plugins.kover)
}

android {
    namespace = "io.github.twinsen81.lustro"

    defaultConfig {
        // INTERNET is declared in the library manifest; loopback-bound by default.
        // R8 rules shipped to consumers (keep public API + the NanoHTTPD engine).
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // The hidden-API policy depends on the target SDK, so the instrumented
        // test APK targets what an app built with this library would.
        targetSdk = libs.versions.targetSdk.get().toInt()
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        // kotlin.io.encoding.Base64 is stable since Kotlin 2.2, but experimental
        // in the stdlib 2.0 this module compiles against.
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
    }
}

// --- Kover coverage ----------------------------------------------------------
// `./gradlew :lustro:koverXmlReport` / `:lustro:koverHtmlReport` produce a valid
// Kover report for the runtime module. Generated sources (Versions.kt) are
// excluded so generated constants don't skew numbers.
//
// AGP-built-in-Kotlin caveat (same family as the BCV note in DECISIONS.md):
// Kover 0.9.1's Android variant auto-detection — and therefore its automatic
// instrumentation of the variant unit-test task — keys off the STANDALONE Kotlin
// Android plugin. These modules apply only `com.android.library` (AGP 9 ships
// Kotlin built-in), so Kover neither registers the `debug`/`release` Android
// report variants nor attaches its coverage agent to `testDebugUnitTest`. The
// report task still runs and emits a structurally valid report; collecting real
// line/branch data for :lustro is deferred until a Kover release cooperates with
// AGP built-in Kotlin. CI uploads this report as an artifact regardless.
kover {
    reports {
        filters {
            excludes {
                classes("io.github.twinsen81.lustro.internal.Versions")
            }
        }
    }
}

dependencies {
    // Public SPI + OkHttp convenience facades are part of the api surface.
    api(project(":lustro-api"))
    api(libs.okhttp)

    // Release-safety lint check, bundled into the published AAR as lint.jar so
    // consumers get the "Lustro debug usage outside src/debug" check automatically.
    lintPublish(project(":lustro-lint"))

    // The server engine and the process lifecycle (ProcessLifecycleOwner and
    // DefaultLifecycleObserver only) stay internal to this module. Both are
    // floors that an app's own versions override; see gradle/libs.versions.toml.
    implementation(libs.nanohttpd)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}

lustroLibrary {
    // :lustro and :lustro-noop both declare `io.github.twinsen81:lustro-runtime`,
    // so a consumer cannot resolve BOTH on the same configuration. See
    // build-logic/.../LustroLibraryExtension.kt.
    pairCapability("lustro-runtime")
}

// --- Generated library/protocol constants (replaces BuildConfig) -------------
// Keeps the wire-protocol version and library version in one generated file so
// no public type hard-codes them and BuildConfig can stay disabled.
abstract class GenerateVersionsTask : DefaultTask() {
    @get:Input
    abstract val libraryVersion: Property<String>

    @get:Input
    abstract val protocolVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val pkgDir = outputDir.get().dir("io/github/twinsen81/lustro/internal").asFile
        pkgDir.mkdirs()
        pkgDir.resolve("Versions.kt").writeText(
            """
            |package io.github.twinsen81.lustro.internal
            |
            |/** Generated build constants. Do not edit; see lustro/build.gradle.kts. */
            |internal object Versions {
            |    const val LIBRARY_VERSION: String = "${libraryVersion.get()}"
            |    const val PROTOCOL_VERSION: String = "${protocolVersion.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateLustroVersions =
    tasks.register<GenerateVersionsTask>("generateLustroVersions") {
        libraryVersion.set(project.version.toString())
        protocolVersion.set("1.10")
        outputDir.set(layout.buildDirectory.dir("generated/source/lustroVersions/kotlin"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateLustroVersions,
            GenerateVersionsTask::outputDir,
        )
    }
}
