package io.github.twinsen81.lustro.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** One entry of `checkFacadeParity`: a runtime module, its no-op half, and their facade packages. */
abstract class FacadePair {
    /** The project path of the runtime module, such as `:lustro`. */
    @get:Input
    abstract val runtime: Property<String>

    /** The project path of the no-op module, such as `:lustro-noop`. */
    @get:Input
    abstract val noop: Property<String>

    /** The packages whose public types make the facade. Subpackages are not included. */
    @get:Input
    abstract val packages: ListProperty<String>

    /** The API dump of the runtime module, which its `apiBuild` task writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeApi: RegularFileProperty

    /** The API dump of the no-op module, which its `apiBuild` task writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noopApi: RegularFileProperty
}

/**
 * Fails when the runtime and no-op halves of a pair expose different public facades. It compares
 * the API dumps of their release classes, which each module's `apiBuild` task writes: see
 * [readFacade] and DECISIONS.md ("BCV / apiCheck").
 */
abstract class FacadeParityTask : DefaultTask() {
    @get:Nested
    abstract val pairs: ListProperty<FacadePair>

    /** The API dump of the supertypes that both halves of a pair extend: the one of :lustro-api. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedApi: RegularFileProperty

    init {
        group = "verification"
        description = "Fails if the runtime and no-op halves of a pair expose different public facades " +
            "(the facadeParity { } block of the root build)."
    }

    @TaskAction
    fun check() {
        val entries = pairs.get()
        if (entries.isEmpty()) {
            throw GradleException("checkFacadeParity: the facadeParity { } block of the root build lists no pair.")
        }
        val shared = dump(sharedApi)
        val failures = mutableListOf<String>()
        for (pair in entries) {
            val runtimeName = pair.runtime.get()
            val noopName = pair.noop.get()
            val packages = pair.packages.get()
            val runtime = readFacade(dump(pair.runtimeApi), shared, packages)
            val noop = readFacade(dump(pair.noopApi), shared, packages)
            val failure = compareFacades(runtimeName, noopName, runtime, noop)
            if (failure != null) {
                failures += failure
            } else {
                logger.lifecycle(
                    "checkFacadeParity: OK. $runtimeName and $noopName expose identical public facades " +
                        "(${runtime.types.size} public types, ${runtime.signatures.size} contributed signatures " +
                        "across ${packages.size} packages).",
                )
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString("\n\n"))
        }
    }

    private fun dump(file: RegularFileProperty): Map<String, DumpedClass> =
        file.get().asFile.let { parseApiDump(it.readText(), it.path) }
}
