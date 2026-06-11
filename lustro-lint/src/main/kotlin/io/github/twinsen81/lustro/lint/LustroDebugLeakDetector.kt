package io.github.twinsen81.lustro.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import java.io.File
import java.util.EnumSet

/**
 * Flags debug-only Lustro usage that has leaked into a non-debug source set.
 *
 * Lustro's runtime (`:lustro`) is meant to be a `debugImplementation` dependency,
 * and any code that touches it — a [DEBUG_TAB] subclass, or a
 * `Lustro.builder(...)` / `.addTab(...)` registration — must live under
 * `src/debug/` so it is compiled out of release/main builds. When such usage is
 * reachable from `src/main/` (or `src/release/`), the debug server and its tabs
 * can ship to production, which is exactly the leak this check prevents.
 *
 * Detection is path-based: lint reports a file as offending when its source-set
 * directory is `main`/`release` (or any non-`debug` variant dir) rather than
 * `debug`. This deliberately does not depend on the Gradle dependency graph, so
 * the check also fires in mixed source layouts where the runtime is on the
 * default `implementation` configuration.
 */
public class LustroDebugLeakDetector : Detector(), Detector.UastScanner {

    // --- DebugTab subclasses --------------------------------------------------

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

    // --- Lustro.builder(...) / .addTab(...) calls -----------------------------

    override fun getApplicableMethodNames(): List<String> = listOf(METHOD_BUILDER, METHOD_ADD_TAB)

    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        // Only Lustro's own builder/addTab matter; ignore same-named methods on
        // unrelated types by checking the declaring class. `Lustro.builder` is a
        // companion-object function, so its containing class may resolve to
        // `Lustro` or `Lustro.Companion` depending on @JvmStatic/UAST; accept any
        // class whose qualified name is under the `Lustro` type.
        val containingClass = method.containingClass?.qualifiedName ?: return
        if (!isLustroOwned(containingClass)) return
        if (isDebugSourceSet(context)) return
        context.report(
            ISSUE,
            node,
            context.getCallLocation(node, includeReceiver = true, includeArguments = true),
            "`Lustro.${method.name}(...)` is reachable from a non-debug source set; " +
                "Lustro registration must live in `src/debug/` so it is excluded " +
                "from release builds.",
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

    /**
     * True when [qualifiedName] is the `Lustro` facade or one of its members
     * (`Lustro.Builder`, `Lustro.Companion`). Matching the prefix tolerates the
     * companion-object / `@JvmStatic` resolution differences for `builder(...)`.
     */
    private fun isLustroOwned(qualifiedName: String): Boolean =
        qualifiedName == LUSTRO || qualifiedName.startsWith("$LUSTRO.")

    private fun pathSegments(file: File): List<String> =
        file.invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }

    public companion object {
        // Fully-qualified names of the Lustro debug API surface this check tracks.
        private const val DEBUG_TAB = "io.github.twinsen81.lustro.DebugTab"
        private const val LUSTRO = "io.github.twinsen81.lustro.Lustro"
        private const val METHOD_BUILDER = "builder"
        private const val METHOD_ADD_TAB = "addTab"

        /**
         * The single issue raised by this detector: debug-only Lustro usage that
         * is reachable from a non-debug source set.
         */
        @JvmField
        public val ISSUE: Issue =
            Issue.create(
                id = "LustroDebugUsageInRelease",
                briefDescription = "Lustro debug usage outside src/debug",
                explanation =
                    """
                    Lustro's runtime (`:lustro`) is a debug-only tool: it starts a \
                    loopback debug server and exposes app internals. It must be a \
                    `debugImplementation` dependency, and all code that registers \
                    Lustro — `DebugTab` subclasses and `Lustro.builder(...)` / \
                    `.addTab(...)` calls — must live under `src/debug/` so it is \
                    compiled out of release and main builds.

                    When such code is reachable from `src/main/` (or `src/release/`), \
                    the debug server and its tabs can leak into production builds. \
                    Move the offending class or registration into the `debug` source \
                    set (`src/debug/java` or `src/debug/kotlin`).
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
