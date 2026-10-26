# Consumer R8/ProGuard rules shipped with the :lustro-prefs artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsFile { public *; }
-keep public enum io.github.twinsen81.lustro.prefs.ValuePolicy { public *; }
