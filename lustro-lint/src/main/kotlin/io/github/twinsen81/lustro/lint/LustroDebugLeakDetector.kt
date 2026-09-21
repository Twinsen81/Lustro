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
 */
public class LustroDebugLeakDetector : Detector(), Detector.UastScanner {

    override fun applicableSuperClasses(): List<String> = listOf(DEBUG_TAB)

    override fun visitClass(context: JavaContext, declaration: UClass) {
        // Ignore the abstract base itself and any subclass that lives in a debug
        // source set. Only concrete leaks into non-debug source are reported.
        if (declaration.qualifiedName == DEBUG_TAB) return
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
     * True when the analysed file belongs to a `debug` source set. Lint runs per
     * source file, so the source-set directory is derived from the file path:
     * `.../src/<sourceSet>/...`. Any segment named `debug` (e.g. `debug`,
     * `<flavor>Debug` via a `debug`-suffixed dir is not split, so we match the
     * exact `debug` dir and the common `*debug*` flavor dirs case-insensitively)
     * marks the file as debug-only. Files outside any recognised `src/<set>/`
     * layout are treated as non-debug (reported), which is the safe default.
     */
    private fun isDebugSourceSet(context: JavaContext): Boolean {
        val segments = pathSegments(context.file)
        val srcIndex = segments.indexOf("src")
        if (srcIndex == -1 || srcIndex + 1 >= segments.size) {
            // Not in a recognisable Gradle source layout (e.g. a synthetic test
            // file). Treat as non-debug so genuine leaks are not silently passed.
            return false
        }
        val sourceSet = segments[srcIndex + 1].lowercase()
        // `debug`, `<flavor>Debug` (lower-cased -> ends with "debug"), and the
        // generic `androidTestDebug` etc. all count as debug-only source sets.
        return sourceSet == "debug" || sourceSet.endsWith("debug")
    }

    private fun pathSegments(file: File): List<String> =
        file.invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }

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

                    `Lustro.builder(...)` / `.addTab(...)` calls are not flagged — \
                    they compile against whichever facade the variant depends on, \
                    and the no-op facade makes them inert in release.
                    """.trimIndent(),
                category = Category.SECURITY,
                priority = PRIORITY,
                severity = Severity.ERROR,
                implementation =
                    Implementation(
                        LustroDebugLeakDetector::class.java,
                        EnumSet.of(Scope.JAVA_FILE, Scope.TEST_SOURCES),
                    ),
            )

        private const val PRIORITY = 8
    }
}
