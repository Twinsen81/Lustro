# Consumer R8/ProGuard rules shipped with the :lustro-work-noop artifact.
#
# The no-op tab reads nothing; keep only the types that the app's release
# bootstrap references by name.
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab$* { public *; }
# create() takes a WorkMetricsInfoRepository, a compileOnly type: an app that
# has no work-analytics never passes one.
-dontwarn androidx.work.**
