package io.github.twinsen81.lustro.gradle

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

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
        alignLustroVersions()
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

/**
 * Adds a dependency constraint on each other published Lustro module at this module's version, so
 * that the module's Gradle module metadata asks for the same version of all of them. The plugin
 * AARs call the `@ExperimentalDebugRoutes` API of lustro-api, whose binary form can change from
 * one version to the next. Without the constraints, an app that moves one artifact to a new
 * version and keeps another one on an older version, as a Dependabot pull request for one
 * artifact does, fails with `NoSuchMethodError` in its debug build. With them, Gradle takes the
 * highest version that one of the app's Lustro modules asks for, for all of them. A constraint
 * adds no module to the app: the release classpath with the no-op halves does not get the
 * runtime halves.
 */
private fun Project.alignLustroVersions() {
    val module = this
    val others = sortedSetOf<String>()
    rootProject.subprojects.filter { it != module }.forEach { other ->
        other.pluginManager.withPlugin("lustro.publishing") {
            others += other.name
            module.dependencies.constraints.add("api", "${module.group}:${other.name}:${module.version}")
        }
    }
    val check = tasks.register<PublishedConstraintsTask>("checkPublishedConstraints") {
        // vanniktech names the publication "maven" and creates it after the build script runs.
        metadata.set(
            provider { tasks.named("generateMetadataFileForMavenPublication", GenerateModuleMetadata::class.java) }
                .flatMap { task -> task.flatMap { it.outputFile } },
        )
        lustroGroup.set(module.group.toString())
        lustroVersion.set(module.version.toString())
        otherModules.set(provider { others })
    }
    tasks.named("check") { dependsOn(check) }
}
