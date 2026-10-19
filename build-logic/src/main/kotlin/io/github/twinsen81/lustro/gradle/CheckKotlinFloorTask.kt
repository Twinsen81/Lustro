package io.github.twinsen81.lustro.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the compile classpath of what a module publishes resolves kotlin-stdlib to anything
 * but the floor.
 *
 * The module declares the floor's kotlin-stdlib (`coreLibrariesVersion`) and compiles as that
 * Kotlin version, but a dependency can still put a newer stdlib on the compile classpath, and
 * Gradle keeps the higher one. The compiler would then inline that stdlib's function bodies and
 * see its declarations while the POM still names the floor, so the artifact could call what an
 * app on the floor does not have. The consumer check cannot see that: the POM is right. See
 * DECISIONS.md ("Dependency floors").
 */
abstract class CheckKotlinFloorTask : DefaultTask() {
    @get:Input
    abstract val floorVersion: Property<String>

    @get:Input
    abstract val classpathName: Property<String>

    @get:Input
    abstract val stdlibVersions: ListProperty<String>

    init {
        group = "verification"
        description = "Fails when the published compile classpath resolves kotlin-stdlib to a version " +
            "other than the floor (kotlinFloor in gradle/libs.versions.toml)."
    }

    @TaskAction
    fun check() {
        val floor = floorVersion.get()
        val classpath = classpathName.get()
        val resolved = stdlibVersions.get()
        if (resolved.isEmpty()) {
            throw GradleException("checkKotlinFloor: $classpath has no kotlin-stdlib.")
        }
        if (resolved.any { it != floor }) {
            throw GradleException(
                "checkKotlinFloor: $classpath resolves kotlin-stdlib to ${resolved.joinToString()}, " +
                    "not the floor $floor. A dependency requests the newer one: exclude " +
                    "org.jetbrains.kotlin from it, or move the floor (DECISIONS.md, \"Dependency floors\").",
            )
        }
        logger.lifecycle("checkKotlinFloor: $classpath has kotlin-stdlib $floor")
    }
}

internal fun kotlinStdlibVersions(root: ResolvedComponentResult): List<String> {
    val versions = mutableListOf<String>()
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        if (!seen.add(component)) continue
        component.moduleVersion
            ?.takeIf { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-stdlib" }
            ?.let { versions += it.version }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
    }
    return versions
}
