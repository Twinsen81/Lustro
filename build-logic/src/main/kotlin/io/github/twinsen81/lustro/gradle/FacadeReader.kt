package io.github.twinsen81.lustro.gradle

import org.gradle.api.GradleException

/**
 * The public facade of one module: the public types in its facade packages, and the signatures
 * that each of those types contributes itself: its header, with its modifiers and supertypes, and
 * its members.
 */
internal class Facade(val signatures: Set<String>, val types: Set<String>)

/**
 * One class of an API dump that the binary compatibility validator (BCV) writes: the [header]
 * line without its `{`, such as `public final class a/b/C : a/b/D`, and the [members] lines.
 */
internal class DumpedClass(val name: String, val header: String, val supertypes: List<String>, val members: List<String>)

private val classHeader = Regex("""^(.*\bclass) (\S+?)(?: : (.+))?$""")

/** Reads the text of a BCV API dump, the format of an `api/<module>.api` file, by class name. */
internal fun parseApiDump(text: String, source: String): Map<String, DumpedClass> {
    val classes = linkedMapOf<String, DumpedClass>()
    var header: String? = null
    val members = mutableListOf<String>()
    text.lines().forEachIndexed { index, raw ->
        val line = raw.trim()
        when {
            header == null && line.isEmpty() -> Unit
            header == null && line.endsWith("{") -> header = line.removeSuffix("{").trim()
            header != null && line == "}" -> {
                val match = classHeader.matchEntire(header!!)
                    ?: throw GradleException("checkFacadeParity: $source: no class name in the line '$header'.")
                val name = match.groupValues[2]
                val supertypes = match.groupValues[3].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                classes[name] = DumpedClass(name, header!!, supertypes, members.toList())
                header = null
                members.clear()
            }
            header != null && line.isNotEmpty() -> members += line
            header != null -> Unit
            else -> throw GradleException("checkFacadeParity: $source, line ${index + 1}: '$line' is not a class header.")
        }
    }
    if (header != null) throw GradleException("checkFacadeParity: $source: the class '$header' has no closing '}'.")
    return classes
}

/**
 * The facade of a module whose API dump is [module], in the [packages] that make its facade. A
 * subpackage is in the facade only when it is in the list, so `internal` packages stay out.
 *
 * BCV has already removed internal and private members and internal classes, and kept the
 * synthetic `$default` forms that a Kotlin caller of a default argument calls. A public member that
 * a class gets from a supertype that is not public, such as an internal interface, is in the dump
 * of the class itself. Two normalizations remain, for what differs between a runtime and a no-op
 * half without changing the API that a consumer compiles against:
 *  - a member that overrides a member of a public Lustro supertype, from [module] or from
 *    [shared], with the same visibility: a consumer calls it through the supertype either way.
 *  - the synthetic constructor that Kotlin adds so that a companion can call a private
 *    constructor. Its parameters are the private constructor's, and javac refuses to call it.
 */
internal fun readFacade(module: Map<String, DumpedClass>, shared: Map<String, DumpedClass>, packages: List<String>): Facade {
    val known = shared + module
    val facadePackages = packages.map { it.replace('.', '/') }.toSet()
    val signatures = sortedSetOf<String>()
    val types = sortedSetOf<String>()
    for (dumped in module.values) {
        if (dumped.name.substringBeforeLast('/') !in facadePackages) continue
        val type = dumped.name.replace('/', '.')
        types += type
        signatures += "$type :: ${dumped.header}"
        val inherited = inheritedMembers(dumped, known)
        val constructors = dumped.members.filter { "fun <init> " in it && !isSynthetic(it) }.map { descriptorOf(it) }.toSet()
        dumped.members
            .filterNot { overrideKey(it) in inherited }
            .filterNot { isPrivateConstructorAccessor(it, constructors) }
            .forEach { signatures += "$type :: $it" }
    }
    return Facade(signatures, types)
}

/** The keys of the members that [dumped] inherits from its public Lustro supertypes, transitively. */
private fun inheritedMembers(dumped: DumpedClass, known: Map<String, DumpedClass>): Set<String> {
    val inherited = mutableSetOf<String>()
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(dumped.supertypes)
    while (queue.isNotEmpty()) {
        val supertype = known[queue.removeFirst()] ?: continue
        if (!seen.add(supertype.name)) continue
        supertype.members.mapNotNullTo(inherited) { overrideKey(it) }
        queue += supertype.supertypes
    }
    return inherited
}

private val memberLine = Regex("""^(public|protected)\b(.*?)\b(fun|field) (.+)$""")

/**
 * The visibility, kind, name, and descriptor of an instance member, which an override keeps; null
 * for a constructor or a static member, which a class does not inherit.
 */
private fun overrideKey(member: String): String? {
    val match = memberLine.matchEntire(member) ?: return null
    val (visibility, modifiers, kind, rest) = match.destructured
    if (rest.startsWith("<init> ") || "static" in modifiers.split(' ')) return null
    return "$visibility $kind $rest"
}

private fun isSynthetic(member: String): Boolean = memberLine.matchEntire(member)?.groupValues?.get(2)?.split(' ')?.contains("synthetic") == true

private fun descriptorOf(member: String): String = member.substringAfter("fun <init> ")

private const val DEFAULT_CONSTRUCTOR_MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;)V"

/**
 * A synthetic constructor whose last parameter is the marker is either the constructor that a
 * call with default arguments calls, with the parameters of a public constructor and an `int`
 * mask, or the accessor of a private constructor.
 */
private fun isPrivateConstructorAccessor(member: String, constructors: Set<String>): Boolean {
    if ("fun <init> " !in member || !isSynthetic(member)) return false
    val descriptor = descriptorOf(member)
    if (!descriptor.endsWith(DEFAULT_CONSTRUCTOR_MARKER)) return false
    val parameters = descriptor.removeSuffix(DEFAULT_CONSTRUCTOR_MARKER)
    return !(parameters.endsWith("I") && "${parameters.removeSuffix("I")})V" in constructors)
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
        section("Signatures only in $runtimeName (missing from $noopName):", onlyRuntime)
        section("Signatures only in $noopName (missing from $runtimeName):", onlyNoop)
        appendLine()
        append(
            "Both facades MUST expose the same public API so consumer code compiles " +
                "unchanged against either. Add the type/member to BOTH modules, or make it internal.",
        )
    }
}
