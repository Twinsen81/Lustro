package io.github.twinsen81.lustro.gradle

import org.gradle.api.Project
import javax.inject.Inject

/** The `lustroLibrary { }` block of a module that applies `lustro.android-library`. */
abstract class LustroLibraryExtension @Inject constructor(private val project: Project) {
    /**
     * Declares the Gradle capability `<GROUP>:<name>` that both halves of a runtime and no-op
     * pair declare, such as `lustro-runtime` for :lustro and :lustro-noop. Then an app can't
     * resolve both halves on one configuration: Gradle fails the resolution as a capability
     * conflict. The module also declares its own default capability again, so that it stays
     * resolvable by its coordinates.
     *
     * The capability must land in two places to cover both ways to consume the module:
     *  - the consumable `...ApiElements` and `...RuntimeElements` configurations, which back
     *    project (composite) consumption, as the :sample variant split uses; and
     *  - the `...ApiPublication` and `...RuntimePublication` configurations of AGP's
     *    single-variant library publication. They are not consumable, but the Gradle Module
     *    Metadata writer reads the capabilities from them, not from the `...Elements`
     *    configurations. A capability set only on the elements never reaches the published
     *    .module file, and external consumers would get no conflict.
     */
    fun pairCapability(name: String) {
        val shared = "${project.group}:$name:${project.version}"
        val own = "${project.group}:${project.name}:${project.version}"
        project.configurations.configureEach {
            val isElements = isCanBeConsumed && (this.name.endsWith("ApiElements") || this.name.endsWith("RuntimeElements"))
            val isPublication = this.name.endsWith("ApiPublication") || this.name.endsWith("RuntimePublication")
            if (isElements || isPublication) {
                outgoing.capability(shared)
                outgoing.capability(own)
            }
        }
    }
}
