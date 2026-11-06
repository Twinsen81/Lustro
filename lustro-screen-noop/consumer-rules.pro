# Consumer R8/ProGuard rules shipped with the :lustro-screen-noop artifact.
#
# The no-op tab reads nothing; keep only the types that the app's release
# bootstrap references by name.
-keep public class io.github.twinsen81.lustro.screen.ScreenDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.screen.ScreenDebugTab$* { public *; }
-keep public interface io.github.twinsen81.lustro.screen.ScreenContextProvider { public *; }
