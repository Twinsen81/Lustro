# Consumer R8/ProGuard rules shipped with the :lustro-prefs artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsFile { public *; }
-keep public enum io.github.twinsen81.lustro.prefs.ValuePolicy { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsDataStore { public *; }
# DataStore and coroutines are compileOnly: an app that registers no
# PrefsDataStore may have neither, and never runs the code that calls them.
-dontwarn androidx.datastore.**
-dontwarn kotlinx.coroutines.**
