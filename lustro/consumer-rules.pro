# Consumer R8/ProGuard rules shipped with the :lustro runtime artifact.
#
# These are applied in the consuming app's R8 run (debug builds that include the
# real runtime). They keep the public facade surface and the NanoHTTPD engine the
# runtime relies on.

# --- Public facade + SPI -----------------------------------------------------
# Consumers (and the debug browser/agent tooling) reference these by name; keep
# the public API and its members so reflection-free linkage and Java interop hold.
-keep public class io.github.twinsen81.lustro.Lustro { public *; }
-keep public class io.github.twinsen81.lustro.Lustro$* { public *; }
-keep public class io.github.twinsen81.lustro.DebugConfig { public *; }
-keep public class io.github.twinsen81.lustro.DebugConfig$* { public *; }
-keep public class io.github.twinsen81.lustro.LustroStatus { public *; }
-keep public class io.github.twinsen81.lustro.network.NetworkDebugTab { public *; }
-keep public class io.github.twinsen81.lustro.network.NetworkDebugTab$* { public *; }
-keep public class io.github.twinsen81.lustro.network.OkHttpSender { public *; }
-keep public class io.github.twinsen81.lustro.network.MockRuleStorage { public *; }
-keep public class io.github.twinsen81.lustro.network.SharedPreferencesMockRuleStorage { public *; }
-keep public class io.github.twinsen81.lustro.network.DefaultRedactor { public *; }

# Subclasses of the public tab SPI are discovered/instantiated by host code; keep
# the abstract base and its overridable members.
-keep public class io.github.twinsen81.lustro.DebugTab { public protected *; }

# --- NanoHTTPD engine --------------------------------------------------------
# The internal LustroServer subclasses NanoHTTPD and the engine touches some
# members reflectively / via overridden hooks. Keep the engine to avoid R8
# stripping classes the server expects to exist at runtime.
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
