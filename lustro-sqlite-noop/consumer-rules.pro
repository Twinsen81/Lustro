# Consumer R8/ProGuard rules shipped with the :lustro-sqlite-noop artifact.
#
# The no-op tab opens nothing; keep only the types that the app's release
# bootstrap references by name.
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDb { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.SqliteDb$* { public *; }
-keep public class io.github.twinsen81.lustro.sqlite.TableHint { public *; }
# SqliteDb.support takes a SupportSQLiteDatabase supplier, a compileOnly type:
# an app that registers none may not have androidx.sqlite.
-dontwarn androidx.sqlite.**
