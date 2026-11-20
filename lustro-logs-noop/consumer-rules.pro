# Consumer R8/ProGuard rules shipped with the :lustro-logs-noop artifact.
#
# No -keep rule. R8 sees the whole app, so it keeps what the app's release code
# calls, and the no-op half loads nothing by reflection. A -keep rule would keep
# the classes, with their names, in every minified release build, also in one
# that never calls Lustro. The sample's checkReleaseHasNoLustroClasses fails
# when a class of its release build has a Lustro name.
