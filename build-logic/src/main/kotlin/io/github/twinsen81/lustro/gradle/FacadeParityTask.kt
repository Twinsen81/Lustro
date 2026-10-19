package io.github.twinsen81.lustro.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeClasses: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noopClasses: DirectoryProperty
}

/**
 * Fails when the runtime and no-op halves of a pair expose different public facades. BCV does not
 * dump the AGP-built Kotlin Android modules, so the task compares their compiled release classes
 * with javap: see [FacadeReader] and DECISIONS.md ("BCV / apiCheck").
 */
abstract class FacadeParityTask : DefaultTask() {
    @get:Nested
    abstract val pairs: ListProperty<FacadePair>

    /** The supertypes that both halves of a pair extend, such as the :lustro-api classes. */
    @get:Classpath
    abstract val sharedClasses: ConfigurableFileCollection

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
        val reader = FacadeReader(javapExecutable(), sharedClasses.files)
        val failures = mutableListOf<String>()
        for (pair in entries) {
            val runtimeName = pair.runtime.get()
            val noopName = pair.noop.get()
            val packages = pair.packages.get()
            val runtime = reader.read(pair.runtimeClasses.get().asFile, packages)
            val noop = reader.read(pair.noopClasses.get().asFile, packages)
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

    private fun javapExecutable(): String {
        val javap = File(System.getProperty("java.home"), "bin/javap")
        return if (javap.canExecute() || File("${javap.path}.exe").exists()) javap.absolutePath else "javap"
    }
}
