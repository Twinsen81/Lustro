// checkCliVersion keeps the version of the lustro-cli package equal to the
// library's. The package's only version is __version__ in the file below, which
// pyproject.toml reads, and it must be VERSION_NAME without -SNAPSHOT.
//
// release.yml publishes the library and the CLI from one tag. PyPI refuses a
// second upload of a version, so a CLI version that a release did not change
// would fail the PyPI job after Maven Central has published, and the GitHub
// Release would attach the wheel of the earlier version.
//
// It also keeps the wire protocol version that the CLI implements, PROTOCOL_VERSION
// in the same file, equal to the server's, the protocolVersion that
// lustro/build.gradle.kts gives to Versions.PROTOCOL_VERSION. `lustro --version`
// prints it, and the CLI warns when an app's protocol is newer, so a change to the
// protocol must update both.

tasks.register("checkCliVersion") {
    group = "verification"
    description =
        "Fails if __version__ of lustro-cli differs from VERSION_NAME in gradle.properties " +
        "without -SNAPSHOT, or its PROTOCOL_VERSION differs from the server's (see gradle/cli-version.gradle.kts)."

    val path = "lustro-cli/src/lustro_cli/__init__.py"
    val serverPath = "lustro/build.gradle.kts"
    val versionName = providers.gradleProperty("VERSION_NAME")
    val source = layout.projectDirectory.file(path)
    val serverSource = layout.projectDirectory.file(serverPath)
    inputs.property("versionName", versionName)
    inputs.file(source)
    inputs.file(serverSource)

    doLast {
        val name = versionName.get()
        val expected = name.removeSuffix("-SNAPSHOT")
        val text = source.asFile.readText()

        fun single(
            file: String,
            fileText: String,
            regex: Regex,
            what: String,
        ): String {
            val found = regex.findAll(fileText).map { it.groupValues[1] }.toList()
            if (found.size != 1) {
                throw GradleException("checkCliVersion: $file must assign $what once, found ${found.size}.")
            }
            return found.single()
        }

        val cliVersion = single(path, text, Regex("""(?m)^__version__\s*=\s*"([^"]*)""""), "__version__")
        if (cliVersion != expected) {
            throw GradleException(
                "checkCliVersion FAILED: lustro-cli is $cliVersion, expected $expected " +
                    "(VERSION_NAME $name without -SNAPSHOT). Update __version__ in $path.",
            )
        }

        val cliProtocol = single(path, text, Regex("""(?m)^PROTOCOL_VERSION\s*=\s*"([^"]*)""""), "PROTOCOL_VERSION")
        val serverProtocol =
            single(
                serverPath,
                serverSource.asFile.readText(),
                Regex("""protocolVersion\.set\("([^"]*)"\)"""),
                "protocolVersion.set(\"...\")",
            )
        if (cliProtocol != serverProtocol) {
            throw GradleException(
                "checkCliVersion FAILED: lustro-cli implements wire protocol $cliProtocol, and the server's is " +
                    "$serverProtocol (protocolVersion in $serverPath). Set PROTOCOL_VERSION = \"$serverProtocol\" " +
                    "in $path.",
            )
        }
        logger.lifecycle(
            "checkCliVersion: OK. lustro-cli is $expected (VERSION_NAME is $name), and implements wire " +
                "protocol $cliProtocol.",
        )
    }
}
