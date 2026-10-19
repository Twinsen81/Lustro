package io.github.twinsen81.lustro.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * `lustro.android-library`: an Android library that Lustro publishes, such as either half of a
 * runtime and no-op pair. The module sets its own `android.namespace` and dependencies. Apply
 * `lustro.publishing` after this plugin to publish it, and use `lustroLibrary { }` to declare the
 * capability of a pair.
 */
class AndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        // AGP 9 provides built-in Kotlin; no org.jetbrains.kotlin.android plugin.
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            compileSdk = libs.version("compileSdk").toInt()
            // Library resources are namespaced so they cannot collide with consumer or
            // other-library resources.
            resourcePrefix = "lustro_"
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
            }
            // No library BuildConfig. A module that needs build constants generates them,
            // as :lustro does with Versions.kt.
            buildFeatures {
                buildConfig = false
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        val kotlin = extensions.getByType<KotlinAndroidProjectExtension>()
        configureKotlin(kotlin, kotlin.compilerOptions, floorClasspath = "releaseCompileClasspath")
        configureDetekt()
        extensions.create<LustroLibraryExtension>("lustroLibrary", this)
    }
}
