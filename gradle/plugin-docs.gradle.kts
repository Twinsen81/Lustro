// checkPluginDocs keeps the docs of each plugin tab complete, and the links in
// the two files that people and agents read first valid.
//
// A plugin module is a module `lustro-<name>` that applies lustro.publishing
// and has a `lustro-<name>-noop` half that applies it too. `lustro` and
// `lustro-noop` are not plugin modules: the Network tab is in the main artifact
// and has its own pages. The check finds the modules from the build, so a new
// pair needs no entry here. For each one, it fails when:
//   - docs/plugins/<name>.md is missing, or its sections are not the sections
//     of docs/plugins/_template.md, in the same order;
//   - the `nav` of zensical.toml does not list plugins/<name>.md;
//   - llms.txt has no link to the page;
//   - context7.json does not list `lustro-<name>/` in `folders`;
//   - the modules table, docs/reference/modules.md, has no row for one half.
//
// It also reads the links in README.md and llms.txt, which the strict site
// build does not read. A link to raw.githubusercontent.com/Twinsen81/Lustro/main/
// must name a file in the repository, and a link to
// https://twinsen81.github.io/Lustro/ must name a page that the site builds, or
// a file that docs.yml publishes. So a renamed or moved page fails here, and
// does not leave a broken link in those files.

val pluginTemplate = "docs/plugins/_template.md"
val modulesTable = "docs/reference/modules.md"
val siteConfig = "zensical.toml"
val llmsTxt = "llms.txt"
val context7 = "context7.json"
val filesWithLinks = listOf("README.md", llmsTxt)

val rawBase = "https://raw.githubusercontent.com/Twinsen81/Lustro/main/"
val siteBase = "https://twinsen81.github.io/Lustro/"
val linkPattern = Regex(
    """(?:${Regex.escape(rawBase)}|${Regex.escape(siteBase)})[^\s()<>\[\]"'`]*""",
)
val h2Pattern = Regex("""^##[ \t]+(.+?)[ \t]*#*[ \t]*$""")
val fencePattern = Regex("""^[ \t]*(```|~~~)""")

// The modules that apply lustro.publishing, by name. Filled while the modules
// are configured, which is after the root project, and read when the task runs.
val publishedModules = sortedSetOf<String>()
subprojects {
    val module = this
    pluginManager.withPlugin("lustro.publishing") { publishedModules += module.name }
}

fun pluginModules(): List<String> =
    publishedModules.filter {
        it.startsWith("lustro-") && !it.endsWith("-noop") && "$it-noop" in publishedModules
    }

fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

// The level-2 headings of a Markdown file, without those inside code blocks.
fun sectionsOf(text: String): List<String> {
    var fence: String? = null
    val sections = mutableListOf<String>()
    for (line in text.lines()) {
        val marker = fencePattern.find(line)?.groupValues?.get(1)
        when {
            fence != null -> if (marker == fence) fence = null
            marker != null -> fence = marker
            else -> h2Pattern.find(line)?.let { sections += it.groupValues[1] }
        }
    }
    return sections
}

// True when [path] names a file in the repository with exactly this case. A
// file system that ignores case, as on macOS, would find `docs/agents.md` for
// docs/AGENTS.md, but GitHub and the site would not.
fun existsInRepo(path: String): Boolean {
    var dir = rootDir
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.any { it == "." || it == ".." }) return false
    for (segment in segments) {
        if (dir.list()?.contains(segment) != true) return false
        dir = File(dir, segment)
    }
    return dir.isFile
}

// The paths that `exclude_docs` in zensical.toml leaves out of the site,
// relative to docs/. Each entry there is a plain path.
fun excludedDocs(config: String): Set<String> =
    Regex("""(?s)exclude_docs\s*=\s*""${'"'}(.*?)""${'"'}""").find(config)?.groupValues?.get(1)
        ?.lines()?.map { it.trim().removePrefix("/") }?.filter { it.isNotEmpty() }?.toSet()
        .orEmpty()

// The `nav = [ ... ]` block of zensical.toml: from its first line to the `]` at
// the start of a line that closes it, without the lines that are comments, so
// that an entry that is commented out does not count.
fun navOf(config: String): String =
    Regex("""(?ms)^nav\s*=\s*\[.*?^]""").find(config)?.value.orEmpty()
        .lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")

// Why a link to the site, by its [path] after the site's base URL, names nothing
// that the site has, or null when the site has it. The site builds a page from
// each Markdown file in docs/ that `exclude_docs` does not leave out, and serves
// no Markdown. It also has the files in docs/, the wire-protocol files that
// docs.yml copies, and the Dokka API reference under api/, which is not in the
// repository and is not checked.
fun siteLinkProblem(path: String, excluded: Set<String>): String? {
    val trimmed = path.trim('/')
    if (trimmed == "api" || trimmed.startsWith("api/")) return null
    val isFile = '.' in trimmed.substringAfterLast('/')
    if (trimmed.startsWith("wire-protocol/") && isFile) {
        return if (existsInRepo(trimmed)) null else "$trimmed is not in the repository"
    }
    if (isFile) {
        return when {
            trimmed in excluded -> "exclude_docs in zensical.toml leaves docs/$trimmed out of the site"
            !existsInRepo("docs/$trimmed") -> "docs/$trimmed is not there"
            trimmed.endsWith(".md") -> {
                val page = trimmed.removeSuffix(".md")
                val url = if (page == "index" || page.endsWith("/index")) page.removeSuffix("index") else "$page/"
                "the site serves no Markdown; the page of docs/$trimmed is $siteBase$url"
            }
            else -> null
        }
    }
    val candidates = if (trimmed.isEmpty()) listOf("index.md") else listOf("$trimmed.md", "$trimmed/index.md")
    val sources = candidates.filter { existsInRepo("docs/$it") }
    return when {
        sources.any { it !in excluded } -> null
        sources.isNotEmpty() -> "exclude_docs in zensical.toml leaves docs/${sources.first()} out of the site"
        else -> "docs/${candidates.first()} is not there"
    }
}

// The repository files that the links in README.md and llms.txt name, so that
// the task runs again when one of them is added, moved, or removed.
fun linkedRepoFiles(): List<File> =
    filesWithLinks.map { rootProject.file(it) }.filter { it.isFile }.flatMap { file ->
        linkPattern.findAll(file.readText()).mapNotNull { match ->
            val url = match.value.substringBefore('#').substringBefore('?').trimEnd('.', ',', ';', ':')
            if (url.startsWith(rawBase)) rootProject.file(url.removePrefix(rawBase)) else null
        }.toList()
    }.distinct()

tasks.register("checkPluginDocs") {
    group = "verification"
    description =
        "Fails if a plugin module has no docs page, nav line, llms.txt link, context7.json folder, or " +
        "modules table row, or if README.md or llms.txt links a docs file or page that is not there " +
        "(see gradle/plugin-docs.gradle.kts)."

    val report = layout.buildDirectory.file("docs-checks/checkPluginDocs.txt")
    inputs.property("pluginModules", provider { pluginModules() })
    inputs.files(filesWithLinks + siteConfig + context7)
    inputs.files(fileTree("docs"))
    inputs.files(provider { linkedRepoFiles() })
    outputs.file(report)

    doLast {
        val problems = mutableListOf<String>()
        fun read(path: String): String? = rootProject.file(path).takeIf { it.isFile }?.readText()

        val template = read(pluginTemplate)
            ?: throw GradleException("checkPluginDocs: $pluginTemplate is missing.")
        val templateSections = sectionsOf(template)
        // A missing shared file is one problem, not one for each module.
        fun readShared(path: String): String? = read(path).also {
            if (it == null) problems += "$path is missing."
        }
        val config = readShared(siteConfig)
        val nav = config?.let(::navOf)
        if (config != null && nav.isNullOrEmpty()) problems += "$siteConfig: no `nav = [ ... ]` block."
        val excluded = config?.let(::excludedDocs).orEmpty()
        val llms = readShared(llmsTxt)
        val table = readShared(modulesTable)
        @Suppress("UNCHECKED_CAST")
        val folders = readShared(context7)?.let {
            (groovy.json.JsonSlurper().parseText(it) as? Map<String, Any?>)?.get("folders") as? List<Any?>
                ?: emptyList()
        }

        val modules = pluginModules()
        for (module in modules) {
            val name = module.removePrefix("lustro-")
            val page = "docs/plugins/$name.md"
            val pageText = read(page)
            if (pageText == null) {
                problems += "$page is missing: copy $pluginTemplate to it and fill it in."
            } else {
                val sections = sectionsOf(pageText)
                if (sections != templateSections) {
                    problems += "$page: its sections are \"${sections.joinToString("\", \"")}\". " +
                        "Use the sections of $pluginTemplate, in its order: " +
                        "\"${templateSections.joinToString("\", \"")}\"."
                }
            }
            if (!nav.isNullOrEmpty() &&
                !Regex("""=\s*"plugins/${Regex.escape(name)}\.md"""").containsMatchIn(nav)
            ) {
                problems += "$siteConfig: the nav does not list plugins/$name.md. Add it to the \"Plugins\" section."
            }
            val rawLink = "(${rawBase}docs/plugins/$name.md)"
            val siteLink = "(${siteBase}plugins/$name/)"
            if (llms != null && rawLink !in llms && siteLink !in llms) {
                problems += "$llmsTxt: no link to $page. Add \"- [<Tab> plugin]$rawLink: <what it covers>\" " +
                    "to its \"## Docs\" list."
            }
            if (folders != null && "$module/" !in folders) {
                problems += "$context7: \"folders\" does not list \"$module/\". Add it."
            }
            for (half in listOf(module, "$module-noop")) {
                if (table != null &&
                    !Regex("""(?m)^\|\s*`:${Regex.escape(half)}`\s*\|""").containsMatchIn(table)
                ) {
                    problems += "$modulesTable: the modules table has no row for `:$half`. Add one."
                }
            }
        }

        var links = 0
        for (path in filesWithLinks) {
            val text = read(path) ?: continue
            linkPattern.findAll(text).forEach { match ->
                val url = match.value.trimEnd('.', ',', ';', ':')
                val target = url.substringBefore('#').substringBefore('?')
                val line = lineOf(text, match.range.first)
                links++
                if (target.startsWith(rawBase)) {
                    val file = target.removePrefix(rawBase)
                    if (!existsInRepo(file)) {
                        problems += "$path:$line: $url names $file, which is not in the repository. " +
                            "Update the link in $path."
                    }
                } else {
                    siteLinkProblem(target.removePrefix(siteBase), excluded)?.let { reason ->
                        problems += "$path:$line: $url names nothing that the site has: $reason. " +
                            "Update the link in $path."
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("checkPluginDocs FAILED:")
                    problems.forEach { appendLine("  $it") }
                    append("See \"Adding a plugin module pair\" and \"Documentation site\" in CONTRIBUTING.md.")
                },
            )
        }
        val summary = "checkPluginDocs: OK. ${modules.size} plugin modules (${modules.joinToString()}) have " +
            "their docs, and the $links links in ${filesWithLinks.joinToString(" and ")} name files and " +
            "pages that exist."
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(summary + "\n")
        logger.lifecycle(summary)
    }
}
