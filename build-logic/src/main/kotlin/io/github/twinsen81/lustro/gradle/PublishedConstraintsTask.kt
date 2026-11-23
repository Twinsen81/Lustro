package io.github.twinsen81.lustro.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the Gradle module metadata of a published module does not ask for its own version of
 * each other published Lustro module, in each variant that an app compiles or runs with. The
 * constraints come from `lustro.publishing`; this task checks that they reach the `.module` file
 * that Maven Central gets.
 */
abstract class PublishedConstraintsTask : DefaultTask() {
    /** The `.module` file that the publication's `generateMetadataFileFor...Publication` task writes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadata: RegularFileProperty

    @get:Input
    abstract val lustroGroup: Property<String>

    @get:Input
    abstract val lustroVersion: Property<String>

    /** The names of the other published modules. */
    @get:Input
    abstract val otherModules: SetProperty<String>

    init {
        group = "verification"
        description = "Fails if the published Gradle module metadata does not ask for this version of every other Lustro module."
    }

    @TaskAction
    fun check() {
        val file = metadata.get().asFile
        val problems = missingConstraints(file.readText(), lustroGroup.get(), lustroVersion.get(), otherModules.get())
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkPublishedConstraints: $file does not ask for version ${lustroVersion.get()} of every other " +
                    "Lustro module:\n" + problems.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle(
            "checkPublishedConstraints: OK. The module metadata asks for version ${lustroVersion.get()} of " +
                "${otherModules.get().size} other Lustro modules.",
        )
    }
}

/**
 * The problems of the Gradle module metadata in [json]: each library variant must have a
 * dependency constraint that requires [version] of `[group]:<module>` for each of [modules].
 */
internal fun missingConstraints(json: String, group: String, version: String, modules: Set<String>): List<String> {
    val root = JsonSlurper().parseText(json) as Map<*, *>
    val variants = (root["variants"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        .filter { (it["attributes"] as? Map<*, *>)?.get("org.gradle.category") == "library" }
    if (variants.isEmpty()) return listOf("no library variant")
    val problems = mutableListOf<String>()
    for (variant in variants) {
        val required = (variant["dependencyConstraints"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
            .filter { it["group"] == group }
            .associate { it["module"] as? String to (it["version"] as? Map<*, *>)?.get("requires") }
        for (module in modules.sorted()) {
            val found = required[module]
            when {
                module !in required -> problems += "${variant["name"]}: no constraint on $group:$module"
                found != version -> problems += "${variant["name"]}: $group:$module requires $found, not $version"
            }
        }
    }
    return problems
}
