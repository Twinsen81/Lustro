# Consumer R8/ProGuard rules shipped with the :lustro-logs-noop artifact.
#
# The no-op tab reads nothing; keep only the entry point that the app's release
# bootstrap references by name.
-keep public class io.github.twinsen81.lustro.logs.LogsDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.logs.LogsDebugTab$* { public *; }
