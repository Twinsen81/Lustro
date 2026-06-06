# Sample app R8 rules.
#
# The release variant consumes :lustro-noop (the no-op artifact) and runs R8 to
# exercise the consumer-rules.pro shipped by the Lustro modules. Library keep
# rules come from each module's consumer-rules.pro, so this file stays minimal.

# The Application subclass is referenced from AndroidManifest.xml; AGP keeps
# manifest-referenced classes automatically, but we keep it explicitly so the
# parity-gate entry point is never stripped.
-keep class io.github.twinsen81.lustro.sample.SampleApplication { *; }
