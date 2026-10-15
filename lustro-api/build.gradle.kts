import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.vanniktech.publish)
    `java-library`
}

// :lustro-api is the pure-Kotlin public SPI. It carries NO Android, OkHttp, or
// server-engine dependencies — only consumer-facing contract types and the
// concrete types consumers construct themselves (responses, send results).
// Explicit-API strict mode (every public declaration needs explicit visibility
// and an explicit return type) and binary-compatibility validation are enforced
// for this module.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Same Kotlin floor as :lustro: the jar is compiled as Kotlin 2.0 against
// kotlin-stdlib 2.0.21, so it carries 2.0 metadata and requires that stdlib.
// See DECISIONS.md ("Dependency floors").
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
        // kotlin.io.encoding.Base64 is stable since Kotlin 2.2, but experimental
        // in the stdlib 2.0 this module compiles against.
        optIn.add("kotlin.io.encoding.ExperimentalEncodingApi")
        // The compiler deprecates the floor's language version before it drops it
        // (Kotlin 2.3 deprecates 2.0) and would warn on every build. Dropping it
        // is still an error, which is when the floor must move.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}

dependencies {
    // @RestrictTo only — CLASS retention, so it stays off the runtime classpath
    // and :lustro-api remains a dependency-free pure-Kotlin SPI jar at runtime.
    // Its own kotlin-stdlib stays off the compile classpath too: it is newer
    // than the floor, and Gradle would keep the higher one (checkKotlinFloor).
    compileOnly(libs.androidx.annotation) {
        exclude(group = "org.jetbrains.kotlin")
    }
}

// checkKotlinFloor: the compile classpath must hold the floor's kotlin-stdlib,
// not a newer one a dependency brings. See gradle/kotlin-floor.gradle.kts.
extra["kotlinFloorVersion"] = kotlinFloor
extra["kotlinFloorClasspath"] = "compileClasspath"
apply(from = rootProject.file("gradle/kotlin-floor.gradle.kts"))

// Library detekt rules (shared config at config/detekt/detekt.yml): explicit
// return types, no public data classes, and public-API documentation. Only
// src/main is analysed so the rules never fire on tests or internal helpers.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/kotlin")
}

// --- Publishing (Vanniktech → Sonatype Central Portal) -----------------------
// POM fields are read automatically from the POM_* gradle.properties. This is a
// pure-Kotlin/JVM library, so the artifact is a plain jar + a Dokka-generated
// Javadoc jar + a sources jar. Signing is OPT-IN (CI only): see the gate below,
// so `publishToMavenLocal` works without GPG keys.
mavenPublishing {
    publishToMavenCentral()
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        System.getenv("ORG_GRADLE_PROJECT_signingInMemoryKey") != null
    ) {
        signAllPublications()
    }
}
