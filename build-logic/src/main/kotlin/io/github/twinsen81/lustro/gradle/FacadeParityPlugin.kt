package io.github.twinsen81.lustro.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.register
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
            sharedApi.set(apiDumpOf(":lustro-api"))
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
                it.runtimeApi.set(project.apiDumpOf(runtime))
                it.noopApi.set(project.apiDumpOf(noop))
            },
        )
    }
}
