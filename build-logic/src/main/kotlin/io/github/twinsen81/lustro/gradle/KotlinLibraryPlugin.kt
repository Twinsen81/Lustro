package io.github.twinsen81.lustro.gradle

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * `lustro.kotlin-library`: a pure Kotlin/JVM library that Lustro publishes, such as
 * :lustro-api. Apply `lustro.publishing` after this plugin to publish it.
 */
class KotlinLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("java-library")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        val kotlin = extensions.getByType<KotlinJvmProjectExtension>()
        configureKotlin(kotlin, kotlin.compilerOptions, floorClasspath = "compileClasspath")
        configureDetekt()
    }
}
