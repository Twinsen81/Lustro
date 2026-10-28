# Consumer R8/ProGuard rules shipped with the :lustro-work artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab$* { public *; }
# WorkManager and work-analytics are compileOnly: the app brings its own
# WorkManager, and an app without work-analytics never runs the code that
# calls it.
-dontwarn androidx.work.**
