# Sample app R8 rules.
#
# The release variant takes the no-op halves and runs R8 over them with the
# consumer-rules.pro that they ship, which keep nothing. The release build must
# have no class with a Lustro name: see checkReleaseHasNoLustroClasses in
# build.gradle.kts.

# The Application subclass is referenced from AndroidManifest.xml; AGP keeps
# manifest-referenced classes automatically, but we keep it explicitly so the
# parity-gate entry point is never stripped.
-keep class io.github.twinsen81.lustro.sample.SampleApplication { *; }
