# Consumer R8/ProGuard rules shipped with the :lustro-noop artifact.
#
# The no-op build opens no socket, loads no NanoHTTPD engine, and captures
# nothing, so there is no reflectively-loaded runtime to keep. The facade is
# free to be shrunk away when unused; we keep only the entry points consumers
# reference by name so release builds that touch the API still link.
-keep public class io.github.twinsen81.lustro.Lustro { public *; }
-keep public class io.github.twinsen81.lustro.Lustro$* { public *; }
-keep public class io.github.twinsen81.lustro.network.NetworkDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.network.NetworkDebugTab$* { public *; }
