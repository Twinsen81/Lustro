// checkDocsVersion keeps the Maven coordinates in the integration docs equal to
// GROUP and VERSION_NAME in gradle.properties. People and agents copy those
// coordinates into their builds without reading anything else, so a stale group
// or version resolves to the wrong artifact or to none.
//
// It reads every `group:lustro...[:version]` coordinate, every version-catalog
// library with a Lustro name (its group, its inline `version`, and the entry its
// `version.ref` names), and every `lustro = "<version>"` catalog entry in the
// docs below. It fails when a group is not GROUP, when the docs name more
// than one version, or when the version is not VERSION_NAME. One exception:
// while VERSION_NAME is the -SNAPSHOT that follows a release, the docs can keep
// that earlier release, which people can resolve from Maven Central. A release
// commit, whose VERSION_NAME has no -SNAPSHOT, must update them all.

val docsWithVersion = listOf("README.md", "llms.txt", ".agents/skills/lustro/SKILL.md")
val docsWithCoordinates = docsWithVersion + "context7.json"

val coordinatePattern = Regex("""\b([A-Za-z][\w-]*(?:\.[\w-]+)+):(lustro[\w-]*)(?::(\d[\w.+-]*))?""")
val catalogTablePattern = Regex("""\{[^{}\n]*}""")
val lustroLibraryPattern = Regex("""\bname\s*=\s*"lustro[\w-]*"|\bmodule\s*=\s*"[^"]*:lustro[\w-]*"""")
val tableGroupPattern = Regex("""\bgroup\s*=\s*"([^"]+)"""")
val tableVersionPattern = Regex("""\bversion\s*=\s*"([^"]+)"""")
val tableVersionRefPattern = Regex("""\bversion\.ref\s*=\s*"([^"]+)"""")
val catalogVersionPattern = Regex("""(?m)^[ \t]*lustro[ \t]*=[ \t]*"([^"]+)"""")
val numericCorePattern = Regex("""^(\d+)\.(\d+)\.(\d+)""")

data class Found(val file: String, val line: Int, val value: String)

fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

fun numericCore(version: String): List<Int>? =
    numericCorePattern.find(version)?.groupValues?.drop(1)?.map { it.toInt() }

// True when [documented] is a release older than the release that the
// -SNAPSHOT [versionName] leads to. A pre-release of that same version, such as
// 0.2.0-beta01 before 0.2.0, counts as older.
fun isEarlierRelease(documented: String, versionName: String): Boolean {
    if (!versionName.endsWith("-SNAPSHOT") || documented.endsWith("-SNAPSHOT")) return false
    val next = numericCore(versionName) ?: return false
    val doc = numericCore(documented) ?: return false
    for (i in 0 until 3) {
        if (doc[i] != next[i]) return doc[i] < next[i]
    }
    return documented.length > numericCorePattern.find(documented)!!.value.length
}

tasks.register("checkDocsVersion") {
    group = "verification"
    description =
        "Fails if the Maven coordinates in the integration docs differ from GROUP and " +
        "VERSION_NAME in gradle.properties (see gradle/docs-version.gradle.kts)."

    val expectedGroup = providers.gradleProperty("GROUP")
    val versionName = providers.gradleProperty("VERSION_NAME")
    inputs.property("group", expectedGroup)
    inputs.property("versionName", versionName)
    inputs.files(docsWithCoordinates.map { rootProject.file(it) })

    doLast {
        val group = expectedGroup.get()
        val version = versionName.get()
        val groups = mutableListOf<Found>()
        val versions = mutableListOf<Found>()

        for (path in docsWithCoordinates) {
            val file = rootProject.file(path)
            if (!file.isFile) throw GradleException("checkDocsVersion: $path is missing.")
            val text = file.readText()
            coordinatePattern.findAll(text).forEach { match ->
                val line = lineOf(text, match.range.first)
                groups += Found(path, line, match.groupValues[1])
                match.groupValues[3].trimEnd('.').takeIf { it.isNotEmpty() }?.let {
                    versions += Found(path, line, it)
                }
            }
            catalogTablePattern.findAll(text)
                .filter { lustroLibraryPattern.containsMatchIn(it.value) }
                .forEach { table ->
                    val line = lineOf(text, table.range.first)
                    tableGroupPattern.find(table.value)?.let { groups += Found(path, line, it.groupValues[1]) }
                    tableVersionPattern.find(table.value)?.let { versions += Found(path, line, it.groupValues[1]) }
                    tableVersionRefPattern.find(table.value)?.let { ref ->
                        val key = Regex.escape(ref.groupValues[1])
                        Regex("""(?m)^[ \t]*$key[ \t]*=[ \t]*"([^"]+)"""").findAll(text).forEach {
                            versions += Found(path, lineOf(text, it.range.first), it.groupValues[1])
                        }
                    }
                }
            catalogVersionPattern.findAll(text).forEach { match ->
                versions += Found(path, lineOf(text, match.range.first), match.groupValues[1])
            }
        }

        val docOrder = docsWithCoordinates.withIndex().associate { it.value to it.index }
        val byPlace = compareBy<Found>({ docOrder.getValue(it.file) }, { it.line })
        groups.sortWith(byPlace)
        // The `lustro = "..."` entry is found both on its own and through a
        // `version.ref`, so drop the second copy.
        val sortedVersions = versions.distinct().sortedWith(byPlace)
        versions.clear()
        versions += sortedVersions

        val problems = mutableListOf<String>()
        groups.filter { it.value != group }.forEach {
            problems += "${it.file}:${it.line}: group ${it.value}, expected $group"
        }
        docsWithVersion.filter { path -> versions.none { it.file == path } }.forEach {
            problems += "$it: names no Lustro version; expected $version"
        }
        val documented = versions.map { it.value }.distinct()
        if (documented.size > 1) {
            problems += "The docs name more than one version: ${documented.joinToString()}"
            versions.forEach { problems += "  ${it.file}:${it.line}: ${it.value}" }
        } else if (documented.size == 1) {
            val only = documented.single()
            if (only != version && !isEarlierRelease(only, version)) {
                problems += "The docs name version $only, expected $version"
                versions.forEach { problems += "  ${it.file}:${it.line}" }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("checkDocsVersion FAILED: the integration docs don't match gradle.properties.")
                    problems.forEach { appendLine(it) }
                    append("Update the coordinates in ${docsWithCoordinates.joinToString()}.")
                },
            )
        }
        logger.lifecycle(
            "checkDocsVersion: OK. ${groups.size} coordinates use group $group, and the docs name " +
                "version ${documented.single()} (VERSION_NAME is $version).",
        )
    }
}
