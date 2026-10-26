# Consumer R8/ProGuard rules shipped with the :lustro-prefs-noop artifact.
#
# The no-op tab reads nothing; keep only the types that the app's release
# bootstrap references by name.
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsFile { public *; }
-keep public enum io.github.twinsen81.lustro.prefs.ValuePolicy { public *; }
-keep public class io.github.twinsen81.lustro.prefs.PrefsDataStore { public *; }
# PrefsDataStore takes a DataStore<Preferences>, a compileOnly type: an app
# that registers no data store may not have DataStore.
-dontwarn androidx.datastore.**
