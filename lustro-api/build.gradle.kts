import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // @RestrictTo only — CLASS retention, so it stays off the runtime classpath
    // and :lustro-api remains a dependency-free pure-Kotlin SPI jar at runtime.
    compileOnly(libs.androidx.annotation)
}

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
    // Pin the Central Portal: the no-arg default targets the retired OSSRH host.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
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
