// =============================================================================
// checkFacadeParity — mechanical public-facade equivalence gate for the two
// runtime facades :lustro (real) and :lustro-noop (release-safe no-op).
//
// WHY THIS EXISTS
// ---------------
// Binary Compatibility Validator (BCV 0.18.1) does NOT register apiDump/apiCheck
// for the AGP-9 / built-in-Kotlin Android modules :lustro and :lustro-noop (it
// keys off the standalone Kotlin Android plugin; see DECISIONS.md "BCV /
// apiCheck"). So there is no committed .api baseline to diff the two facades
// against. This task fills that gap WITHOUT BCV: it extracts the public facade
// signatures straight from each module's compiled release classes with `javap`
// (always present in the toolchain JDK), normalizes them, and FAILS on any
// difference. The cross-variant :sample compile already catches drift at compile
// time; this gives an explicit, standalone, CI-runnable check too.
//
// WHAT "FACADE" MEANS HERE
// ------------------------
// The consumer-facing surface in the packages `io.github.twinsen81.lustro` and
// `io.github.twinsen81.lustro.network` (the `*.internal.*` packages are excluded).
//
// NORMALIZATION (so the comparison reflects the CONSUMER API, not internal
// plumbing that legitimately differs between a real runtime and a no-op):
//   1. Only the two facade packages above; drop `internal` and synthetic
//      anonymous classes (names containing `$<digit>`).
//   2. Drop Kotlin `internal` members — they compile to public JVM methods with
//      a mangled `name$module` suffix (the module token is build-dependent, so the
//      shape is matched generically) — and synthetic bridges (`access$...`).
//   3. Drop constructors. Consumers never call facade constructors directly;
//      they go through `Lustro.builder(...)`, `DebugConfig.builder()`, and the
//      `NetworkDebugTab.create(...)` / `OkHttpSender` factories. The real
//      runtime's primary constructors carry internal DI wiring (e.g. an internal
//      `DebugTabRegistry`) that the no-op does not need.
//   4. Drop members a facade type merely INHERITS or OVERRIDES from a supertype
//      (compared by exact signature). Members from the shared `:lustro-api` SPI
//      (e.g. `DebugTab.onStart/handle/schema/...`) are already BCV-validated and
//      are identical on both sides; members from an INTERNAL supertype (e.g. the
//      internal `NetworkCaptureProvider` that the real `NetworkDebugTab` plugs
//      into) are not consumer API. What remains is exactly the NEW public
//      members each facade type contributes itself — which MUST match.
//
// The result is the set of `(class :: member)` signatures each module's facade
// adds beyond its public supertypes. The two sets must be byte-for-byte equal.
// Adding a facade method to only one side changes one set and fails the task.
//
// The check ALSO diffs the SET of public facade TYPE names on each side, so a
// one-sided public type that declares zero members (an empty marker/annotation, or
// a subclass whose members are all inherited) — invisible to the member diff —
// fails too. And every `javap` invocation is checked for a non-zero exit code, so a
// class javap cannot resolve fails the task loudly instead of silently contributing
// nothing.
// =============================================================================

val facadePackages = listOf(
    "io/github/twinsen81/lustro",
    "io/github/twinsen81/lustro/network",
)

// Compiled release classes for an AGP built-in-Kotlin module live here.
fun releaseClassesDir(moduleName: String): File =
    project(":$moduleName").layout.buildDirectory
        .dir("intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes")
        .get().asFile

// :lustro-api compiled classes — needed on the classpath so `javap` can resolve
// supertypes that live in the shared SPI (e.g. DebugTab).
fun apiClassesDir(): File =
    project(":lustro-api").layout.buildDirectory
        .dir("classes/kotlin/main").get().asFile

/** Result of one `javap` invocation: its stdout lines plus the process exit code. */
data class JavapResult(val lines: List<String>, val exitCode: Int, val stderr: String)

/**
 * Run `javap` for [fqcn] under [classpath] and return its stdout lines, exit code,
 * and stderr. A non-zero exit code (e.g. a class javap cannot resolve) MUST be
 * surfaced by callers: a silently-failed javap contributes zero members, which
 * would let real facade drift slip past the parity diff (see B7/B2 in DECISIONS).
 */
fun javap(javapExe: String, classpath: String, fqcn: String): JavapResult {
    val proc = ProcessBuilder(javapExe, "-public", "-classpath", classpath, fqcn)
        .redirectErrorStream(false)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val err = proc.errorStream.bufferedReader().readText()
    val code = proc.waitFor()
    return JavapResult(out.lines(), code, err.trim())
}

/** Lines-only convenience wrapper for callers that don't inspect the exit code. */
fun javapLines(javapExe: String, classpath: String, fqcn: String): List<String> =
    javap(javapExe, classpath, fqcn).lines

/**
 * The method-name token of a javap member line, or null for a field/header line.
 *
 * javap prints a method as `<modifiers> <returnType> <name>(<params>);`, so the
 * name is the last whitespace-delimited token of the substring before the first
 * `(`. The return type may itself be a nested type whose binary name contains `$`
 * (e.g. `...Lustro$Builder builder(...)`), but it is a SEPARATE, space-delimited
 * token, so isolating the token immediately before `(` yields the bare member
 * name (`builder`) and never the type's `$`.
 */
fun methodNameToken(memberLine: String): String? {
    val beforeParen = memberLine.substringBefore('(', missingDelimiterValue = "")
    if (beforeParen.isEmpty() || beforeParen == memberLine) return null // no '(' -> field
    return beforeParen.trim().split(Regex("""\s+""")).lastOrNull()
}

// A Kotlin `internal` member compiles to a public JVM method whose name is mangled
// to `<name>$<module>` (e.g. `onForeground$lustro`, `bindTo$lustro_noop`). The
// module suffix is build/module-dependent, so match the SHAPE generically: a bare
// identifier, a `$`, then a module-ish suffix — and nothing else. Legitimate public
// Kotlin members never contain a `$` in their JVM name, so this cannot drop real API.
val mangledInternalName = Regex("""^\w+\$\w+$""")

/** True if [memberLine] is a Kotlin `internal` member (mangled `name$module(...)`). */
fun isMangledInternal(memberLine: String): Boolean =
    methodNameToken(memberLine)?.let { mangledInternalName.matches(it) } == true

/** Parse the `extends X implements Y, Z` supertypes out of a javap class header. */
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

// Modifier keywords that differ between a DECLARED member (concrete, on a class)
// and the SAME member as seen on a supertype (e.g. `abstract` on an interface,
// `final` once overridden). Stripping them lets the inherited-subtraction compare
// an override against its supertype declaration by signature alone.
val modifierKeywords = setOf(
    "public", "protected", "static", "final", "abstract", "default",
    "synchronized", "native", "transient", "volatile", "strictfp",
)

/** Drop leading modifier keywords and collapse whitespace for stable comparison. */
fun canonicalize(member: String): String {
    val collapsed = member.replace(Regex("""\s+"""), " ").trim()
    val tokens = collapsed.split(" ")
    val kept = tokens.dropWhile { it in modifierKeywords }
    return kept.joinToString(" ")
}

/** Public, declared members of [fqcn] (no constructors/mangled/synthetic), canonicalized. */
fun declaredMembers(javapExe: String, classpath: String, fqcn: String): List<String> {
    val simple = fqcn.substringAfterLast('.').substringAfterLast('$')
    // Escape interpolated identifiers so a `$` (or any regex metachar) in a binary
    // name cannot corrupt the pattern (B7). `simple` may carry a `$` for a nested
    // type; the FQCN does too.
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

/**
 * One module's normalized facade: the per-type contributed-member signatures AND
 * the set of public type FQCNs. The member set alone is blind to a one-sided public
 * TYPE that declares zero members (an empty marker/annotation, or a subclass whose
 * members are all inherited) — so the type set is diffed independently (B2).
 */
data class Facade(val signatures: List<String>, val types: Set<String>)

/** True if a javap class header line declares a `public` type (class/interface/enum/@interface). */
fun isPublicTypeHeader(headerLine: String): Boolean =
    headerLine.contains(Regex("""\bpublic\b""")) &&
        headerLine.contains(Regex("""\b(class|interface|enum)\b"""))

/**
 * Fail the task loudly if javap could not resolve [fqcn]. A mis-resolved class
 * otherwise contributes zero members silently, widening the parity blind spot (B7).
 */
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

/** The normalized facade (member signatures + public type FQCNs) for one module. */
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
            // Track every PUBLIC facade type, even one that contributes no declared
            // members, so a one-sided empty type fails the diff (B2).
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

    // The compiled release classes are the inputs.
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

        // A facade with no public TYPES is as suspect as one with no members:
        // refuse to pass vacuously on either dimension.
        if (real.types.isEmpty() || noop.types.isEmpty()) {
            error(
                "checkFacadeParity extracted an empty facade type set " +
                    "(real=${real.types.size}, noop=${noop.types.size}); refusing to pass vacuously.",
            )
        }

        // Type-level drift (B2): a one-sided public type, even one that declares
        // zero members, is invisible to the member diff below. Diff the type sets.
        val onlyRealTypes = real.types - noop.types
        val onlyNoopTypes = noop.types - real.types

        // Member-level drift: the contributed-member signatures must match exactly.
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
