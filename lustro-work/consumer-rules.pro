# Consumer R8/ProGuard rules shipped with the :lustro-work artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.work.WorkDebugTab$* { public *; }
# WorkManager and work-analytics are compileOnly: the app brings its own
# WorkManager, and an app without work-analytics never runs the code that
# calls it.
-dontwarn androidx.work.**
# The tab reads the unique name and the prerequisites of each work request from
# WorkManager's database by reflection (WorkRecords), in a debug build that R8
# minifies too.
-keep class androidx.work.impl.WorkManagerImpl { public androidx.work.impl.WorkDatabase getWorkDatabase(); }
-keep class androidx.work.impl.WorkDatabase {
    public androidx.work.impl.model.WorkNameDao workNameDao();
    public androidx.work.impl.model.DependencyDao dependencyDao();
}
-keep interface androidx.work.impl.model.WorkNameDao { public java.util.List getNamesForWorkSpecId(java.lang.String); }
-keep interface androidx.work.impl.model.DependencyDao { public java.util.List getPrerequisites(java.lang.String); }
