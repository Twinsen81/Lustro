// checkCliVersion keeps the version of the lustro-cli package equal to the
// library's. The package's only version is __version__ in the file below, which
// pyproject.toml reads, and it must be VERSION_NAME without -SNAPSHOT.
//
// release.yml publishes the library and the CLI from one tag. PyPI refuses a
// second upload of a version, so a CLI version that a release did not change
// would fail the PyPI job after Maven Central has published, and the GitHub
// Release would attach the wheel of the earlier version.

tasks.register("checkCliVersion") {
    group = "verification"
    description =
        "Fails if __version__ of lustro-cli differs from VERSION_NAME in gradle.properties " +
        "without -SNAPSHOT (see gradle/cli-version.gradle.kts)."

    val path = "lustro-cli/src/lustro_cli/__init__.py"
    val versionName = providers.gradleProperty("VERSION_NAME")
    val source = layout.projectDirectory.file(path)
    inputs.property("versionName", versionName)
    inputs.file(source)

    doLast {
        val name = versionName.get()
        val expected = name.removeSuffix("-SNAPSHOT")
        val found = Regex("""(?m)^__version__\s*=\s*"([^"]*)"""")
            .findAll(source.asFile.readText())
            .map { it.groupValues[1] }
            .toList()
        if (found.size != 1) {
            throw GradleException("checkCliVersion: $path must assign __version__ once, found ${found.size}.")
        }
        if (found.single() != expected) {
            throw GradleException(
                "checkCliVersion FAILED: lustro-cli is ${found.single()}, expected $expected " +
                    "(VERSION_NAME $name without -SNAPSHOT). Update __version__ in $path.",
            )
        }
        logger.lifecycle("checkCliVersion: OK. lustro-cli is $expected (VERSION_NAME is $name).")
    }
}
