import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    // AGP 9 provides built-in Kotlin; no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish)
}

// :lustro-noop is the release-safe artifact. It exposes the SAME public facades
// as :lustro (so consumer code compiles unchanged) but every runtime body is a
// no-op. It depends on :lustro-api and OkHttp so it can mirror the OkHttp-typed
// facades (networkInterceptor(), OkHttpSender, NetworkDebugTab.create). The
// Gradle capability that makes :lustro and :lustro-noop mutually exclusive is
// declared in the mutual-exclusion block below.

android {
    namespace = "io.github.twinsen81.lustro.noop"
    compileSdk = libs.versions.compileSdk.get().toInt()
    // Mirror :lustro: namespace library resources so they cannot collide with
    // consumer or other-library resources.
    resourcePrefix = "lustro_"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // R8 rules shipped to consumers (minimal: keep public facade entry points).
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Same Kotlin floor as :lustro: Kotlin 2.0 metadata and kotlin-stdlib 2.0.21,
// so the release-safe AAR does not raise an app's Kotlin either. See
// DECISIONS.md ("Dependency floors").
val kotlinFloor = libs.versions.kotlinFloor.get()
val kotlinFloorVersion = KotlinVersion.fromVersion(kotlinFloor.substringBeforeLast('.'))

kotlin {
    explicitApi()
    jvmToolchain(21)
    coreLibrariesVersion = kotlinFloor
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(kotlinFloorVersion)
        apiVersion.set(kotlinFloorVersion)
        // Since Kotlin 2.2 an interface's default methods are JVM default methods
        // (with DefaultImpls kept for older callers), but only for language
        // version 2.2 and up: 2.0 alone would go back to DefaultImpls only, a
        // different public API that apiCheck rejects and that breaks Java
        // implementers of the SPI interfaces.
        jvmDefault.set(JvmDefaultMode.ENABLE)
        // The compiler deprecates the floor's language version before it drops it
        // (Kotlin 2.3 deprecates 2.0) and would warn on every build. Dropping it
        // is still an error, which is when the floor must move.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}

// checkKotlinFloor: the published variant's compile classpath must hold the
// floor's kotlin-stdlib, not a newer one a dependency brings. See
// gradle/kotlin-floor.gradle.kts.
extra["kotlinFloorVersion"] = kotlinFloor
extra["kotlinFloorClasspath"] = "releaseCompileClasspath"
apply(from = rootProject.file("gradle/kotlin-floor.gradle.kts"))

// Library detekt rules (shared config at config/detekt/detekt.yml): explicit
// return types, no public data classes, and public-API documentation. Only
// src/main is analysed so the rules never fire on tests; the UndocumentedPublic*
// rules are scoped in the config so they do not fire on internal/private code.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    source.setFrom("src/main/kotlin")
}

// --- Publishing (Vanniktech → Sonatype Central Portal) -----------------------
// The release-safe artifact. Mirrors :lustro's publishing config: single
// "release" variant AAR + sources + Dokka Javadoc jar, POM from POM_* props.
// Signing is OPT-IN (CI only) so `publishToMavenLocal` works without GPG keys.
mavenPublishing {
    publishToMavenCentral()
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

dependencies {
    api(project(":lustro-api"))
    api(libs.okhttp)
}

// --- Mutual-exclusion capability ---------------------------------------------
// Mirrors :lustro: both modules declare the shared capability
// `io.github.twinsen81:lustro-runtime`, so a consumer cannot resolve BOTH on the
// same configuration. We also re-declare this module's own default capability so
// it stays individually resolvable. The capability is set on BOTH the consumable
// `...Elements` configs (project/composite consumption) AND AGP's dedicated
// `...ApiPublication` / `...RuntimePublication` configs, since the Gradle Module
// Metadata writer reads capabilities from the latter — see lustro/build.gradle.kts
// for the full rationale.
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
