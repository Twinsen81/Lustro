package io.github.twinsen81.lustro.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import org.jetbrains.uast.UClass
import java.io.File
import java.util.EnumSet

/**
 * Flags [DEBUG_TAB] subclasses that have leaked into a non-debug source set.
 *
 * A `DebugTab` subclass is the consumer's OWN code: it is compiled into whatever
 * variant its source set belongs to, and it typically reaches straight into app
 * internals (feature flags, storage, session state). Swapping `:lustro` for
 * `:lustro-noop` in release does not make that class disappear from the APK, so
 * it must live under `src/debug/`.
 *
 * `Lustro.builder(...)` / `.addTab(...)` calls are deliberately NOT flagged: those
 * resolve to the facade the variant depends on, and the `:lustro-noop` release
 * facade makes them inert. Wiring the runtime from a shared `Application` is a
 * supported layout, so flagging it would fail the documented quick start.
 *
 * Detection is path-based: lint reports a file as offending when its source-set
 * directory is `main`/`release` (or any non-`debug` variant dir) rather than
 * `debug`. This deliberately does not depend on the Gradle dependency graph, so
 * the check also fires in mixed source layouts where the runtime is on the
 * default `implementation` configuration.
 *
 * Test sources (`src/test`, `src/androidTest`, and their variants) are not
 * checked, also with `checkTestSources = true`: test code never goes into an APK,
 * and a fake `DebugTab` that a test uses is not a leak.
 *
 * The severity is FATAL so that `lintVital<Variant>`, which `assemble<Variant>`
 * runs for each release variant and which checks only FATAL issues, fails the
 * release build. `:lustro-noop` ships this check too, so the release lint loads
 * it and reads `src/release` and `src/main`.
 */
public class LustroDebugLeakDetector : Detector(), Detector.UastScanner {

    override fun applicableSuperClasses(): List<String> = listOf(DEBUG_TAB)

    override fun visitClass(context: JavaContext, declaration: UClass) {
        // Ignore the abstract base itself and any subclass that lives in a debug
        // source set. Only concrete leaks into non-debug source are reported.
        if (declaration.qualifiedName == DEBUG_TAB) return
        // Test code never goes into an APK. The scope leaves out test sources, but
        // `checkTestSources = true` makes lint run every issue on them.
        if (context.isTestSource) return
        if (isDebugSourceSet(context)) return
        context.report(
            ISSUE,
            declaration,
            context.getNameLocation(declaration),
            "`DebugTab` subclass is reachable from a non-debug source set; move it " +
                "to `src/debug/` so it is excluded from release builds.",
        )
    }

    /**
     * True when the analysed file belongs to a debug source set: `debug`, or a
     * `<flavor>Debug` set (the name, in lower case, ends with `debug`). A file
     * outside a recognised `src/<set>/<java|kotlin>/` layout is not debug, so it
     * is reported. This is the safe default.
     *
     * Lint gives no source-set name for a file. `LintModelSourceProvider` has no
     * name, and AGP always sets its `isDebugOnly()` to false. So the set name comes
     * from the path, but only from the part below the module directory
     * (`context.project.dir`): a folder above the module, such as `~/src/` in
     * `~/src/MyApp/app/src/debug/kotlin/...`, is not part of the parsed path.
     */
    private fun isDebugSourceSet(context: JavaContext): Boolean {
        val sourceSet = sourceSetName(context.file, context.project.dir) ?: return false
        return sourceSet.lowercase().endsWith("debug")
    }

    public companion object {
        // Fully-qualified name of the Lustro debug API surface this check tracks.
        private const val DEBUG_TAB = "io.github.twinsen81.lustro.DebugTab"

        /**
         * The single issue raised by this detector: a `DebugTab` subclass that is
         * reachable from a non-debug source set.
         */
        @JvmField
        public val ISSUE: Issue =
            Issue.create(
                id = "LustroDebugUsageInRelease",
                briefDescription = "Lustro DebugTab outside src/debug",
                explanation =
                    """
                    Lustro's runtime (`:lustro`) is a debug-only tool: it starts a \
                    loopback debug server and exposes app internals. It must be a \
                    `debugImplementation` dependency, with `:lustro-noop` on the \
                    release configuration.

                    A `DebugTab` subclass is your own code, so the `:lustro-noop` \
                    swap cannot make it disappear: whatever source set it lives in \
                    is compiled into that variant, taking the app internals it \
                    reads with it. Move the offending class into the `debug` source \
                    set (`src/debug/java` or `src/debug/kotlin`).

                    The severity is fatal, so the lint that `assembleRelease` runs \
                    fails the release build while such a class is outside \
                    `src/debug`.

                    `Lustro.builder(...)` / `.addTab(...)` calls are not flagged — \
                    they compile against whichever facade the variant depends on, \
                    and the no-op facade makes them inert in release.
                    """.trimIndent(),
                category = Category.SECURITY,
                priority = PRIORITY,
                severity = Severity.FATAL,
                implementation =
                    Implementation(
                        LustroDebugLeakDetector::class.java,
                        EnumSet.of(Scope.JAVA_FILE),
                    ),
            )

        private const val PRIORITY = 8

        private val SOURCE_LANGUAGE_DIRS = setOf("java", "kotlin")

        /**
         * The name of the source set that contains [file], or null when [file] is
         * not in a `src/<set>/<java|kotlin>/` layout.
         *
         * When [file] is below [moduleDir], the first `src/<set>/<java|kotlin>`
         * match in the path below [moduleDir] wins. That match is the module's
         * own source folder: a package folder named `src`, `java`, or `kotlin`
         * comes after it, so it cannot win.
         *
         * When [file] is not below [moduleDir] (a source folder that the build
         * adds from outside the module), the absolute path is the only data. Then
         * the last match wins, so a folder named `src` above the project cannot
         * win.
         */
        internal fun sourceSetName(file: File, moduleDir: File?): String? {
            val relative = moduleDir?.let { relativeSegments(file, it) }
            if (relative != null) {
                return sourceSetMatches(relative).firstOrNull()
            }
            return sourceSetMatches(pathSegments(file.absoluteFile)).lastOrNull()
        }

        // Each `<set>` in a `src/<set>/<java|kotlin>` triple, in path order.
        private fun sourceSetMatches(segments: List<String>): List<String> =
            (0 until segments.size - 2)
                .filter { segments[it] == "src" && segments[it + 2] in SOURCE_LANGUAGE_DIRS }
                .map { segments[it + 1] }

        // The path segments of [file] below [dir], or null when [file] is not below [dir].
        private fun relativeSegments(file: File, dir: File): List<String>? {
            val fileSegments = pathSegments(file.absoluteFile)
            val dirSegments = pathSegments(dir.absoluteFile)
            if (fileSegments.size <= dirSegments.size) return null
            if (fileSegments.subList(0, dirSegments.size) != dirSegments) return null
            return fileSegments.subList(dirSegments.size, fileSegments.size)
        }

        private fun pathSegments(file: File): List<String> =
            file.invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }
    }
}
