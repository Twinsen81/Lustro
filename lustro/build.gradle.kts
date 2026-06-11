import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 provides built-in Kotlin; no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.kover)
}

android {
    namespace = "io.github.twinsen81.lustro"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // Library resources are namespaced so they cannot collide with consumer or
    // other-library resources.
    resourcePrefix = "lustro_"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // INTERNET is declared in the library manifest; loopback-bound by default.
        // R8 rules shipped to consumers (keep public API + the NanoHTTPD engine).
        consumerProguardFiles("consumer-rules.pro")
    }

    // No library BuildConfig — protocol/library constants are generated into
    // Versions.kt (see the generateLustroVersions task below).
    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Library detekt rules (shared config at config/detekt/detekt.yml): explicit
// return types, no public data classes, and public-API documentation. Only
// src/main is analysed so the rules never fire on tests; the UndocumentedPublic*
// rules are scoped in the config so they do not fire on internal/private code.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin")
}

// --- Publishing (Vanniktech → Sonatype Central Portal) -----------------------
// POM fields are read automatically from the POM_* gradle.properties. As an
// Android library we publish a single "release" variant AAR + a sources jar +
// a Dokka-generated Javadoc jar. The "release" variant is what consumers get;
// debug-only runtime deps (none are `api`/`implementation` on release here) are
// therefore NOT leaked into the published POM. Signing is OPT-IN (CI only): see
// the gate below, so `publishToMavenLocal` works without GPG keys.
mavenPublishing {
    // Pin the Central Portal: the no-arg default targets the retired OSSRH host.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
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

    // Server engine and Android runtime support stay internal to this module.
    implementation(libs.nanohttpd)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}

// --- Mutual-exclusion capability ---------------------------------------------
// :lustro and :lustro-noop both declare the shared capability
// `io.github.twinsen81:lustro-runtime`, so a consumer cannot resolve BOTH on the
// same configuration (Gradle fails the resolution as a capability conflict). We
// also re-declare each module's own default capability (group:name:version) so
// the modules stay individually resolvable.
//
// The capability must land in TWO places to cover both consumption paths:
//   - the consumable `...ApiElements` / `...RuntimeElements` configs, which back
//     PROJECT (composite) consumption — this is what the :sample variant split
//     relies on; and
//   - AGP's single-variant library publication, which maps the published
//     component variants to DEDICATED, non-consumable `...ApiPublication` /
//     `...RuntimePublication` configurations. The Gradle Module Metadata writer
//     reads capabilities from THOSE, not from the `...Elements` configs, so a
//     capability set only on the elements configs never reaches the published
//     .module (verified: external consumers would get no conflict). Decorating
//     the `...Publication` configs is what carries the capability into the GMM.
// Configured eagerly via configureEach; both config families exist at
// configuration time, so no afterEvaluate is needed.
run {
    val lustroRuntimeCapability = "io.github.twinsen81:lustro-runtime:${project.version}"
    val defaultCapability = "io.github.twinsen81:${project.name}:${project.version}"
    configurations.configureEach {
        val isElements = isCanBeConsumed && (name.endsWith("ApiElements") || name.endsWith("RuntimeElements"))
        val isPublication = name.endsWith("ApiPublication") || name.endsWith("RuntimePublication")
        if (isElements || isPublication) {
            outgoing.capability(lustroRuntimeCapability)
            outgoing.capability(defaultCapability) // keep default so the module stays resolvable
        }
    }
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
        protocolVersion.set("1.0")
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
