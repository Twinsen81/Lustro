package io.github.twinsen81.lustro

/**
 * Marks the API that declares a tab's routes: [DebugTab.routes], [DebugRoutes], and the
 * types that a declaration uses.
 *
 * The shape of this API can still change in a release, in a way that breaks the code
 * that uses it, both at the source and the binary level. The opt-in is an error, not a
 * warning, so that a tab says that it accepts this with `@OptIn(ExperimentalDebugRoutes::class)`.
 * A tab is debug code that the app builds with the same Lustro version that it runs, so a
 * change shows up as a compile error in the tab, not as a crash in another library.
 */
@RequiresOptIn(
    message = "The API that declares a tab's routes can still change in a way that breaks code that uses it. " +
        "Opt in with @OptIn(ExperimentalDebugRoutes::class) to accept this.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalDebugRoutes
