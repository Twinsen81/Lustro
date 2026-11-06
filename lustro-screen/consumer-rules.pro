# Consumer R8/ProGuard rules shipped with the :lustro-screen artifact.
#
# Keep the public types that the app's debug code calls by name.
-keep public class io.github.twinsen81.lustro.screen.ScreenDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.screen.ScreenDebugTab$* { public *; }
-keep public interface io.github.twinsen81.lustro.screen.ScreenContextProvider { public *; }
# Compose UI is compileOnly: an app without Compose never runs the code that
# reads the semantics tree.
-dontwarn androidx.compose.**
# On API 24 to 28 the tab finds the app's windows through WindowManagerGlobal,
# a platform class that R8 never renames.
