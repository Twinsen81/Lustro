package io.github.twinsen81.lustro.gradle

import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * The API baseline of a published Android module: the tasks `apiBuild`, `apiCheck`, and `apiDump`,
 * which the binary compatibility validator (BCV) registers for :lustro-api, with the baseline in
 * `api/<module>.api`. BCV 0.18 registers them only for a module with the Kotlin Android plugin,
 * and these modules compile with AGP's built-in Kotlin. So this gives BCV's own task types the
 * module's compiled release classes, which are the classes of the published AAR. The
 * `apiValidation { }` block of the root build applies to them, as it does to :lustro-api. See
 * DECISIONS.md ("BCV / apiCheck").
 *
 * `./gradlew apiCheck` runs the check of every module, and `./gradlew apiDump` writes every
 * baseline again.
 */
internal fun Project.configureApiBaseline() {
    // BCV reads the class files with ASM and the Kotlin metadata library. Its worker has them
    // on its own classpath, not on the classpath of the build, as BCV does for :lustro-api.
    val workerDependencies = configurations.dependencyScope("lustroApiDump")
    val workerClasspath = configurations.resolvable("lustroApiDumpClasspath") {
        extendsFrom(workerDependencies.get())
    }
    for (alias in listOf("asm", "asm-tree", "kotlin-metadata-jvm")) {
        dependencies.addProvider(workerDependencies.name, libs.findLibrary(alias).get())
    }

    val baseline = layout.projectDirectory.file("api/$name.api")
    val apiBuild = tasks.register<KotlinApiBuildTask>("apiBuild") {
        description = "Writes the public API of the release classes to build/api/${project.name}.api."
        inputClassesDirs.from(
            compileOutput("compileReleaseKotlin", KotlinCompile::class.java) { it.destinationDirectory },
            compileOutput("compileReleaseJavaWithJavac", JavaCompile::class.java) { it.destinationDirectory },
        )
        outputApiFile.set(layout.buildDirectory.file("api/${project.name}.api"))
        runtimeClasspath.from(workerClasspath)
    }
    val generated: Provider<RegularFile> = apiBuild.flatMap { it.outputApiFile }
    val apiCheck = tasks.register<KotlinApiCompareTask>("apiCheck") {
        group = "verification"
        description = "Fails when the public API differs from api/${project.name}.api. Run apiDump to accept a change."
        projectApiFile.set(baseline)
        generatedApiFile.set(generated)
    }
    tasks.register<Copy>("apiDump") {
        group = "other"
        description = "Writes the public API of the release classes to api/${project.name}.api."
        from(generated)
        into(baseline.asFile.parentFile)
    }
    tasks.named("check") { dependsOn(apiCheck) }
}

/**
 * The output directory of one of a module's compile tasks. Looked up when it is needed, because
 * AGP registers the variant tasks after the module's build script runs.
 */
internal fun <T : Task> Project.compileOutput(
    taskName: String,
    type: Class<T>,
    output: (T) -> Provider<Directory>,
): Provider<Directory> = provider { tasks.named(taskName, type) }.flatMap { task -> task.flatMap(output) }

/**
 * The API dump that the `apiBuild` task of the module at [path] writes: BCV's own task in
 * :lustro-api, and the one of [configureApiBaseline] in an Android module.
 */
internal fun Project.apiDumpOf(path: String): Provider<RegularFile> =
    provider { project(path).tasks.named("apiBuild", KotlinApiBuildTask::class.java) }
        .flatMap { task -> task.flatMap { it.outputApiFile } }
