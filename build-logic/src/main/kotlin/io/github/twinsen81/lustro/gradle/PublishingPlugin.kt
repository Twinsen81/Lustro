package io.github.twinsen81.lustro.gradle

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `lustro.publishing`: publishes the module to the Sonatype Central Portal with the vanniktech
 * plugin. The POM takes the root `POM_*` properties, and `POM_NAME` and `POM_DESCRIPTION` from
 * the module's own gradle.properties; the artifactId is the project name.
 *
 * Apply it after the plugin that makes the module a library, which decides what it publishes:
 *  - `lustro.android-library`: the AAR of the single "release" variant, a sources jar, and a
 *    Javadoc jar from Dokka. Debug-only dependencies stay out of the POM.
 *  - `lustro.kotlin-library`: the jar, a sources jar, and a Javadoc jar from Dokka.
 *  - `java-library` alone, a module without Kotlin: the jar, a sources jar, and an empty Javadoc
 *    jar.
 *
 * The root build adds every module with this plugin to the Dokka site and to the files of the
 * GitHub Release (collectReleaseArtifacts).
 *
 * Signing is opt-in, for CI only: it starts when the in-memory signing key is set, so that
 * `publishToMavenLocal` works without GPG keys.
 */
class PublishingPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val platform = when {
            pluginManager.hasPlugin("com.android.library") -> {
                pluginManager.apply("org.jetbrains.dokka")
                AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
            }
            pluginManager.hasPlugin("org.jetbrains.kotlin.jvm") -> {
                pluginManager.apply("org.jetbrains.dokka")
                KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true)
            }
            pluginManager.hasPlugin("java-library") -> JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = true)
            else -> throw GradleException(
                "$path: apply lustro.publishing after lustro.android-library, lustro.kotlin-library, or java-library.",
            )
        }
        pluginManager.apply("com.vanniktech.maven.publish")
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            configure(platform)
            if (providers.gradleProperty("signingInMemoryKey").isPresent ||
                providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
            ) {
                signAllPublications()
            }
        }
    }
}
