package io.github.twinsen81.lustro.gradle

import org.gradle.api.GradleException
import java.io.File

/**
 * The public facade of one module: the public types in its facade packages, and the member
 * signatures that each of those types contributes itself.
 */
internal class Facade(val signatures: Set<String>, val types: Set<String>)

/**
 * Reads a module's facade from its compiled classes with `javap -public`, and normalizes away
 * what differs between a runtime and a no-op half without changing the API a consumer compiles
 * against: internal and synthetic members, constructors, and the members that a type inherits or
 * overrides from a supertype in `io.github.twinsen81`. A class that javap cannot read is an error,
 * so that drift cannot pass as an empty diff.
 *
 * [sharedClasspath] holds the supertypes that both halves extend, such as the :lustro-api SPI.
 */
internal class FacadeReader(private val javapExecutable: String, private val sharedClasspath: Collection<File>) {

    fun read(classesRoot: File, packages: List<String>): Facade {
        if (!classesRoot.isDirectory) {
            throw GradleException("checkFacadeParity: no classes at $classesRoot. Did the Kotlin compile task run?")
        }
        val classpath = (listOf(classesRoot) + sharedClasspath).joinToString(File.pathSeparator) { it.absolutePath }
        val signatures = sortedSetOf<String>()
        val types = sortedSetOf<String>()

        for (pkg in packages) {
            val pkgDir = File(classesRoot, pkg.replace('.', '/'))
            if (!pkgDir.isDirectory) continue
            // Direct children only: a subpackage is a facade package only when it is
            // in the list, so `internal` packages stay out.
            val classFiles = pkgDir.listFiles { f -> f.isFile && f.name.endsWith(".class") }.orEmpty().sortedBy { it.name }
            for (classFile in classFiles) {
                val fqcn = "$pkg.${classFile.name.removeSuffix(".class")}"
                // Skip synthetic anonymous classes: a `$` segment that starts with a digit.
                if (fqcn.split('$').drop(1).any { it.firstOrNull()?.isDigit() == true }) continue

                val header = javap(classpath, fqcn).lines
                // Track every public facade type, even one that contributes no declared
                // members, so that a type on one side only fails the diff.
                val headerLine = header.firstOrNull { it.contains(" class ") || it.contains(" interface ") }
                if (headerLine != null && isPublicTypeHeader(headerLine)) {
                    types += fqcn
                }
                // Gather the members that the supertypes declare, transitively, so that
                // inherited and overridden members can be subtracted.
                val inherited = mutableSetOf<String>()
                val seen = mutableSetOf<String>()
                val queue = ArrayDeque(supertypesOf(header))
                while (queue.isNotEmpty()) {
                    val supertype = queue.removeFirst()
                    if (!seen.add(supertype)) continue
                    val supertypeHeader = javap(classpath, supertype).lines
                    inherited += declaredMembers(supertypeHeader, supertype)
                    queue += supertypesOf(supertypeHeader)
                }
                declaredMembers(header, fqcn)
                    .filterNot { it in inherited }
                    .forEach { signatures += "$fqcn :: $it" }
            }
        }
        return Facade(signatures, types)
    }

    private fun javap(classpath: String, fqcn: String): JavapResult {
        val process = ProcessBuilder(javapExecutable, "-public", "-classpath", classpath, fqcn)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText().trim()
        val code = process.waitFor()
        if (code != 0) {
            throw GradleException(
                "checkFacadeParity: javap failed (exit $code) resolving '$fqcn'. A class javap " +
                    "cannot read contributes no signatures and would hide facade drift. Fix the " +
                    "classpath or the class before trusting this gate." +
                    if (err.isNotEmpty()) "\n  javap stderr: $err" else "",
            )
        }
        return JavapResult(out.lines())
    }

    private class JavapResult(val lines: List<String>)

    private companion object {
        // Kotlin's JVM name for an `internal` member: `name$module`. The module
        // suffix depends on the build, so match any.
        val mangledInternalName = Regex("""^\w+\$\w+$""")

        val modifierKeywords = setOf(
            "public", "protected", "static", "final", "abstract", "default",
            "synchronized", "native", "transient", "volatile", "strictfp",
        )

        fun methodNameToken(memberLine: String): String? {
            val beforeParen = memberLine.substringBefore('(', missingDelimiterValue = "")
            if (beforeParen.isEmpty()) return null // no '(' -> a field
            return beforeParen.trim().split(Regex("""\s+""")).lastOrNull()
        }

        fun isMangledInternal(memberLine: String): Boolean =
            methodNameToken(memberLine)?.let { mangledInternalName.matches(it) } == true

        fun supertypesOf(headerLines: List<String>): List<String> {
            val header = headerLines.firstOrNull { it.contains(" class ") || it.contains(" interface ") }
                ?: return emptyList()
            val result = mutableListOf<String>()
            Regex("""extends\s+([\w.$]+)""").find(header)?.let { result += it.groupValues[1] }
            Regex("""implements\s+([\w.,$\s]+?)(?:\s*\{|$)""").find(header)?.let { match ->
                match.groupValues[1].split(",").map { it.trim().substringBefore("<") }
                    .filter { it.isNotEmpty() }.forEach { result += it }
            }
            // Only Lustro's own supertypes: java.lang.Object, Enum, and the like add
            // nothing that differs between the two halves.
            return result.map { it.substringBefore("<") }.filter { it.startsWith("io.github.twinsen81") }
        }

        fun canonicalize(member: String): String =
            member.replace(Regex("""\s+"""), " ").trim().split(" ").dropWhile { it in modifierKeywords }.joinToString(" ")

        fun declaredMembers(javapLines: List<String>, fqcn: String): List<String> {
            val simple = fqcn.substringAfterLast('.').substringAfterLast('$')
            // Escape the names so that a `$` or another regex metacharacter in a binary
            // name cannot corrupt the constructor patterns.
            val ctorByFqcn = Regex("""(^|\s)${Regex.escape(fqcn)}\(""")
            val ctorBySimple = Regex("""(^|\s)${Regex.escape(simple)}\(""")
            return javapLines
                .map { it.trim() }
                .filter { it.endsWith(";") } // member lines end with ';'
                .filterNot { isMangledInternal(it) }
                .filterNot { it.contains("DefaultConstructorMarker") || it.contains("access\$") }
                // A constructor line is the type name followed by '(' with no return type
                // before it, such as `public <pkg>.<Simple>(...)`.
                .filterNot { ctorByFqcn.containsMatchIn(it) }
                .filterNot { ctorBySimple.containsMatchIn(it) && !it.contains(" $simple ") }
                .map { canonicalize(it) }
        }

        fun isPublicTypeHeader(headerLine: String): Boolean =
            headerLine.contains(Regex("""\bpublic\b""")) && headerLine.contains(Regex("""\b(class|interface|enum)\b"""))
    }
}

/**
 * Compares the facades of a runtime module and its no-op half. Returns the report of what differs,
 * or null when both expose the same public API.
 */
internal fun compareFacades(runtimeName: String, noopName: String, runtime: Facade, noop: Facade): String? {
    if (runtime.types.isEmpty() || noop.types.isEmpty()) {
        return "checkFacadeParity: $runtimeName and $noopName: an empty facade type set " +
            "($runtimeName=${runtime.types.size}, $noopName=${noop.types.size}). Check the facade packages; " +
            "the task refuses to pass when there is nothing to compare."
    }
    val onlyRuntimeTypes = runtime.types - noop.types
    val onlyNoopTypes = noop.types - runtime.types
    val onlyRuntime = runtime.signatures - noop.signatures
    val onlyNoop = noop.signatures - runtime.signatures
    if (onlyRuntimeTypes.isEmpty() && onlyNoopTypes.isEmpty() && onlyRuntime.isEmpty() && onlyNoop.isEmpty()) {
        return null
    }
    return buildString {
        appendLine("Facade parity check FAILED: $runtimeName and $noopName expose different public facades.")
        appendLine(
            "(types: ${runtime.types.size} in $runtimeName / ${noop.types.size} in $noopName; " +
                "signatures: ${runtime.signatures.size} in $runtimeName / ${noop.signatures.size} in $noopName)",
        )
        fun section(title: String, entries: Set<String>) {
            if (entries.isEmpty()) return
            appendLine()
            appendLine(title)
            entries.forEach { appendLine("  + $it") }
        }
        section("Public TYPES only in $runtimeName (missing from $noopName):", onlyRuntimeTypes)
        section("Public TYPES only in $noopName (missing from $runtimeName):", onlyNoopTypes)
        section("Members only in $runtimeName (missing from $noopName):", onlyRuntime)
        section("Members only in $noopName (missing from $runtimeName):", onlyNoop)
        appendLine()
        append(
            "Both facades MUST expose the same public API so consumer code compiles " +
                "unchanged against either. Add the type/member to BOTH modules.",
        )
    }
}
