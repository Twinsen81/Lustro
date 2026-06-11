import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    baseline = file("detekt-baseline.xml")
    source.setFrom("src/main/kotlin")
}

// --- Publishing (Vanniktech → Sonatype Central Portal) -----------------------
// The release-safe artifact. Mirrors :lustro's publishing config: single
// "release" variant AAR + sources + Dokka Javadoc jar, POM from POM_* props.
// Signing is OPT-IN (CI only) so `publishToMavenLocal` works without GPG keys.
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
