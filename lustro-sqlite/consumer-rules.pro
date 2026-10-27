# Consumer R8/ProGuard rules shipped with the :lustro-sqlite artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDb { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDb$* { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.TableHint { public *; }
# androidx.sqlite is compileOnly: an app that registers no SupportSQLiteDatabase
# may not have it, and never runs the code that calls it.
-dontwarn androidx.sqlite.**
