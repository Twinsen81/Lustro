# Consumer R8/ProGuard rules shipped with the :lustro-logs artifact.
#
# Keep the public factory that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.logs.LogsDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.logs.LogsDebugTab$* { public *; }
