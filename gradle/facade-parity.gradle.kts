// checkFacadeParity compares the consumer-facing facades of :lustro and
// :lustro-noop. BCV does not dump these AGP built-in-Kotlin Android modules, so
// this task extracts release-class signatures with `javap` and normalizes away
// implementation-only differences: internal/synthetic members, constructors, and
// inherited members. It also compares public type names and treats javap failures
// as task failures so drift cannot pass as an empty diff.

val facadePackages = listOf(
    "io/github/twinsen81/lustro",
    "io/github/twinsen81/lustro/network",
)

fun releaseClassesDir(moduleName: String): File =
    project(":$moduleName").layout.buildDirectory
        .dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")
        .get().asFile

fun apiClassesDir(): File =
    project(":lustro-api").layout.buildDirectory
        .dir("classes/kotlin/main").get().asFile

data class JavapResult(val lines: List<String>, val exitCode: Int, val stderr: String)

fun javap(javapExe: String, classpath: String, fqcn: String): JavapResult {
    val proc = ProcessBuilder(javapExe, "-public", "-classpath", classpath, fqcn)
        .redirectErrorStream(false)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    val code = proc.waitFor()
    return JavapResult(out.lines(), code, err.trim())
}

fun javapLines(javapExe: String, classpath: String, fqcn: String): List<String> =
    javap(javapExe, classpath, fqcn).lines

fun methodNameToken(memberLine: String): String? {
    val beforeParen = memberLine.substringBefore('(', missingDelimiterValue = "")
    if (beforeParen.isEmpty() || beforeParen == memberLine) return null // no '(' -> field
    return beforeParen.trim().split(Regex("""\s+""")).lastOrNull()
}

// Match Kotlin internal JVM-name mangling generically; the module suffix is build-dependent.
val mangledInternalName = Regex("""^\w+\$\w+$""")

fun isMangledInternal(memberLine: String): Boolean =
    methodNameToken(memberLine)?.let { mangledInternalName.matches(it) } == true

fun supertypesOf(headerLines: List<String>): List<String> {
    val header = headerLines.firstOrNull { it.contains(" class ") || it.contains(" interface ") }
        ?: return emptyList()
    val result = mutableListOf<String>()
    Regex("""extends\s+([\w.$]+)""").find(header)?.let { result += it.groupValues[1] }
    Regex("""implements\s+([\w.,$\s]+?)(?:\s*\{|$)""").find(header)?.let { m ->
        m.groupValues[1].split(",").map { it.trim().substringBefore("<") }
            .filter { it.isNotEmpty() }.forEach { result += it }
    }
    // Strip generic args defensively and ignore java.lang.* / java.lang.Enum etc.
    return result.map { it.substringBefore("<") }
        .filter { it.startsWith("io.github.twinsen81") }
}

val modifierKeywords = setOf(
    "public", "protected", "static", "final", "abstract", "default",
    "synchronized", "native", "transient", "volatile", "strictfp",
)

fun canonicalize(member: String): String {
    val collapsed = member.replace(Regex("""\s+"""), " ").trim()
    val tokens = collapsed.split(" ")
    val kept = tokens.dropWhile { it in modifierKeywords }
    return kept.joinToString(" ")
}

fun declaredMembers(javapExe: String, classpath: String, fqcn: String): List<String> {
    val simple = fqcn.substringAfterLast('.').substringAfterLast('$')
    // Escape interpolated identifiers so `$` or any regex metachar in a binary
    // name cannot corrupt the constructor patterns.
    val ctorByFqcn = Regex("""(^|\s)${Regex.escape(fqcn)}\(""")
    val ctorBySimple = Regex("""(^|\s)${Regex.escape(simple)}\(""")
    return javapLines(javapExe, classpath, fqcn)
        .map { it.trim() }
        .filter { it.endsWith(";") } // member lines end with ';'
        // Drop Kotlin `internal` members (mangled `name$module(...)`) generically.
        .filterNot { isMangledInternal(it) }
        .filterNot { it.contains("DefaultConstructorMarker") || it.contains("access\$") }
        // Constructor lines look like: `public <pkg>.<Simple>(...)` — i.e. the
        // type name immediately followed by '(' with no return type before it.
        .filterNot { ctorByFqcn.containsMatchIn(it) }
        .filterNot { ctorBySimple.containsMatchIn(it) && !it.contains(" $simple ") }
        .map { canonicalize(it) }
}

data class Facade(val signatures: List<String>, val types: Set<String>)

fun isPublicTypeHeader(headerLine: String): Boolean =
    headerLine.contains(Regex("""\bpublic\b""")) &&
        headerLine.contains(Regex("""\b(class|interface|enum)\b"""))

fun JavapResult.requireResolved(fqcn: String): JavapResult {
    if (exitCode != 0) {
        throw GradleException(
            "checkFacadeParity: javap failed (exit $exitCode) resolving '$fqcn'. " +
                "A class javap cannot read contributes no signatures and would hide " +
                "facade drift — fix the classpath/class before trusting this gate." +
                if (stderr.isNotEmpty()) "\n  javap stderr: $stderr" else "",
        )
    }
    return this
}

fun facadeSignatures(javapExe: String, classesRoot: File): Facade {
    if (!classesRoot.isDirectory) {
        error("Facade classes not found at $classesRoot — did compileReleaseKotlin run?")
    }
    val cp = "${classesRoot.absolutePath}${File.pathSeparator}${apiClassesDir().absolutePath}"
    val signatures = sortedSetOf<String>()
    val types = sortedSetOf<String>()

    facadePackages.forEach { pkgPath ->
        val pkgDir = File(classesRoot, pkgPath)
        if (!pkgDir.isDirectory) return@forEach
        // Direct children only (do not descend into the `network` subpackage from
        // the top package, and never into `internal`).
        pkgDir.listFiles { f -> f.isFile && f.name.endsWith(".class") }?.forEach classes@{ classFile ->
            val rel = classFile.relativeTo(classesRoot).path
                .removeSuffix(".class").replace(File.separatorChar, '/')
            if (rel.contains("/internal/")) return@classes
            val fqcn = rel.replace('/', '.')
            // Skip synthetic anonymous classes: a `$` segment that starts with a digit.
            if (fqcn.split('$').drop(1).any { it.firstOrNull()?.isDigit() == true }) return@classes

            val header = javap(javapExe, cp, fqcn).requireResolved(fqcn).lines
            // Track every public facade type, even one that contributes no declared
            // members, so one-sided empty types fail the diff.
            val headerLine = header.firstOrNull { it.contains(" class ") || it.contains(" interface ") }
            if (headerLine != null && isPublicTypeHeader(headerLine)) {
                types += fqcn
            }
            // Gather members declared by all (transitive, io.github.twinsen81.*)
            // supertypes so we can subtract inherited/overridden members.
            val inherited = sortedSetOf<String>()
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(supertypesOf(header))
            while (queue.isNotEmpty()) {
                val s = queue.removeFirst()
                if (!seen.add(s)) continue
                val sHeader = javap(javapExe, cp, s).requireResolved(s).lines
                inherited += declaredMembers(javapExe, cp, s)
                queue += supertypesOf(sHeader)
            }
            declaredMembers(javapExe, cp, fqcn)
                .filterNot { inherited.contains(it) }
                .forEach { signatures += "$fqcn :: $it" }
        }
    }
    return Facade(signatures.toList(), types)
}

val checkFacadeParity by tasks.registering {
    group = "verification"
    description =
        "Fails if the public facades of :lustro and :lustro-noop diverge " +
        "(BCV-Android gap follow-up; see gradle/facade-parity.gradle.kts)."

    dependsOn(
        ":lustro:compileReleaseKotlin",
        ":lustro-noop:compileReleaseKotlin",
        ":lustro-api:compileKotlin",
    )

    doLast {
        val javapExe = File(System.getProperty("java.home"), "bin/javap").absolutePath
            .let { if (File(it).canExecute() || File("$it.exe").exists()) it else "javap" }

        val real = facadeSignatures(javapExe, releaseClassesDir("lustro"))
        val noop = facadeSignatures(javapExe, releaseClassesDir("lustro-noop"))

        if (real.types.isEmpty() || noop.types.isEmpty()) {
            error(
                "checkFacadeParity extracted an empty facade type set " +
                    "(real=${real.types.size}, noop=${noop.types.size}); refusing to pass vacuously.",
            )
        }

        val onlyRealTypes = real.types - noop.types
        val onlyNoopTypes = noop.types - real.types

        val onlyReal = real.signatures - noop.signatures.toSet()
        val onlyNoop = noop.signatures - real.signatures.toSet()

        if (onlyRealTypes.isNotEmpty() || onlyNoopTypes.isNotEmpty() ||
            onlyReal.isNotEmpty() || onlyNoop.isNotEmpty()
        ) {
            val sb = StringBuilder()
            sb.appendLine("Facade parity check FAILED: :lustro and :lustro-noop expose different public facades.")
            sb.appendLine(
                "(types: ${real.types.size} in :lustro / ${noop.types.size} in :lustro-noop; " +
                    "signatures: ${real.signatures.size} in :lustro / ${noop.signatures.size} in :lustro-noop)",
            )
            if (onlyRealTypes.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Public TYPES only in :lustro (missing from :lustro-noop):")
                onlyRealTypes.forEach { sb.appendLine("  + $it") }
            }
            if (onlyNoopTypes.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Public TYPES only in :lustro-noop (missing from :lustro):")
                onlyNoopTypes.forEach { sb.appendLine("  + $it") }
            }
            if (onlyReal.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Members only in :lustro (missing from :lustro-noop):")
                onlyReal.forEach { sb.appendLine("  + $it") }
            }
            if (onlyNoop.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Members only in :lustro-noop (missing from :lustro):")
                onlyNoop.forEach { sb.appendLine("  + $it") }
            }
            sb.appendLine()
            sb.appendLine(
                "Both facades MUST expose the same public API so consumer code compiles " +
                    "unchanged against either. Add the type/member to BOTH modules.",
            )
            throw GradleException(sb.toString())
        }

        logger.lifecycle(
            "checkFacadeParity: OK — :lustro and :lustro-noop expose identical public facades " +
                "(${real.types.size} public types, ${real.signatures.size} contributed signatures " +
                "across ${facadePackages.size} packages).",
        )
    }
}
