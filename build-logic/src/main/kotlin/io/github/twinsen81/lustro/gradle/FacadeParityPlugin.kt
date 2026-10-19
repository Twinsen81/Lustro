package io.github.twinsen81.lustro.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import javax.inject.Inject

/**
 * `lustro.facade-parity`, for the root project: registers `checkFacadeParity`, which compares
 * each pair that the `facadeParity { }` block lists.
 */
class FacadeParityPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val extension = extensions.create<FacadeParityExtension>("facadeParity", this)
        tasks.register<FacadeParityTask>("checkFacadeParity") {
            pairs.set(extension.pairs)
            // Both halves of every pair depend only on :lustro-api (DECISIONS.md, row 7),
            // which holds the supertypes of their facade types.
            sharedClasses.from(kotlinClasses(":lustro-api", "compileKotlin"))
        }
    }
}

/** The `facadeParity { }` block of the root build. */
abstract class FacadeParityExtension @Inject constructor(private val project: Project) {
    abstract val pairs: ListProperty<FacadePair>

    /**
     * Adds a pair: the project paths of the [runtime] module and its [noop] half, and the
     * [packages] whose public types make their facade. A subpackage is compared only when it is
     * in the list.
     */
    fun pair(runtime: String, noop: String, packages: List<String>) {
        pairs.add(
            project.objects.newInstance<FacadePair>().also {
                it.runtime.set(runtime)
                it.noop.set(noop)
                it.packages.set(packages)
                it.runtimeClasses.set(project.kotlinClasses(runtime, "compileReleaseKotlin"))
                it.noopClasses.set(project.kotlinClasses(noop, "compileReleaseKotlin"))
            },
        )
    }
}

/**
 * The output of a module's Kotlin compile task. Looked up when the task runs, because the root
 * project is configured before the modules, and AGP registers the variant tasks later still.
 */
private fun Project.kotlinClasses(path: String, compileTask: String): Provider<Directory> =
    provider { project(path).tasks.named(compileTask, KotlinCompile::class.java) }
        .flatMap { task -> task.flatMap { it.destinationDirectory } }
