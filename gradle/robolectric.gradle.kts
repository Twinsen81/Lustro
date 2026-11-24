// Robolectric runs each test on the android-all jar of the test's SDK level. By
// default it downloads that jar from Maven Central while the test runs, with no
// retry and outside Gradle's cache, so a network error fails each test class of
// that level in its setup. Here Gradle resolves the jars as dependencies, with
// its retries and into its cache, and `robolectricJars` copies them into one
// directory. Each unit test JVM of an Android module reads them from there with
// robolectric.offline=true, and a test downloads nothing.
//
// The keys are the SDK levels that the tests run on: the levels in their
// @Config(sdk = [...]), and targetSdk for a test without one. The values are the
// versions of org.robolectric:android-all-instrumented that Robolectric takes
// for each level, from its DefaultSdkProvider. A test on a level that is not
// here, or a Robolectric release that takes another jar for a level, fails with
// the name of the jar that Robolectric could not find: add or change it here.

val androidAllVersions =
    mapOf(
        28 to "9-robolectric-4913185-2-i7",
        34 to "14-robolectric-10818077-i7",
        35 to "15-robolectric-14663667-2-i7",
    )

// One configuration for each level: in one configuration, Gradle would resolve
// the versions of the one module to the newest.
val androidAllJars =
    androidAllVersions.map { (sdk, version) ->
        configurations.create("robolectricAndroidAll$sdk") {
            isCanBeConsumed = false
            isTransitive = false
            dependencies.add(project.dependencies.create("org.robolectric:android-all-instrumented:$version"))
        }
    }

val robolectricJars =
    tasks.register<Sync>("robolectricJars") {
        description = "Copies the android-all jars that the Robolectric tests run on into one directory."
        from(androidAllJars)
        into(layout.buildDirectory.dir("robolectric-jars"))
    }

// The directory is not an input, so the test results do not depend on where the
// checkout is; the jar versions are.
class RobolectricOffline(
    @get:Internal val dependencyDir: Provider<File>,
) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> =
        listOf(
            "-Drobolectric.offline=true",
            "-Drobolectric.dependency.dir=${dependencyDir.get().absolutePath}",
        )
}

val dependencyDir = robolectricJars.map { it.destinationDir }
subprojects {
    pluginManager.withPlugin("com.android.base") {
        tasks.withType<Test>().configureEach {
            dependsOn(robolectricJars)
            inputs.property("robolectricAndroidAll", androidAllVersions.values.sorted())
            jvmArgumentProviders.add(RobolectricOffline(dependencyDir))
        }
    }
}
