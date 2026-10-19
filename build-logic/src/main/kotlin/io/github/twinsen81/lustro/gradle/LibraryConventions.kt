package io.github.twinsen81.lustro.gradle

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

private const val JDK_TOOLCHAIN = 21

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/**
 * The Kotlin settings of a published module. Toolchain 21 compiles to JVM 17 bytecode, and
 * every public declaration needs an explicit visibility and return type.
 *
 * The published artifacts must not raise an app's Kotlin. They are compiled as the floor's
 * language and API version (`kotlinFloor` in the catalog) against the floor's kotlin-stdlib,
 * whatever compiler this build uses, so they carry the floor's metadata and require that stdlib.
 * See DECISIONS.md ("Dependency floors"). [floorClasspath] is the compile classpath of what the
 * module publishes, which `checkKotlinFloor` reads.
 */
internal fun Project.configureKotlin(
    kotlin: KotlinProjectExtension,
    compilerOptions: KotlinJvmCompilerOptions,
    floorClasspath: String,
) {
    val floor = libs.version("kotlinFloor")
    val floorLanguage = KotlinVersion.fromVersion(floor.substringBeforeLast('.'))
    kotlin.explicitApi()
    kotlin.jvmToolchain(JDK_TOOLCHAIN)
    kotlin.coreLibrariesVersion = floor
    compilerOptions.apply {
        jvmTarget.set(JvmTarget.JVM_17)
        languageVersion.set(floorLanguage)
        apiVersion.set(floorLanguage)
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

    val checkKotlinFloor = tasks.register<CheckKotlinFloorTask>("checkKotlinFloor") {
        floorVersion.set(floor)
        classpathName.set(floorClasspath)
        // The configuration of an Android variant exists only after AGP creates
        // the variants, so it is looked up when the task needs it.
        stdlibVersions.set(
            provider { configurations.getByName(floorClasspath) }
                .flatMap { it.incoming.resolutionResult.rootComponent }
                .map(::kotlinStdlibVersions),
        )
    }
    tasks.named("check") { dependsOn(checkKotlinFloor) }
}

/**
 * The library detekt rules (config/detekt/detekt.yml): explicit return types, no public data
 * classes, and public-API documentation. Only src/main is analysed, so the rules never fire on
 * tests. A module can keep the findings it accepts in `detekt-baseline.xml`, as a no-op half
 * does for its unused parameters: `detektBaseline` writes it, and `detekt` reads it when it
 * exists.
 */
internal fun Project.configureDetekt() {
    pluginManager.apply("io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        source.setFrom("src/main/kotlin")
        baseline = file("detekt-baseline.xml")
    }
}
