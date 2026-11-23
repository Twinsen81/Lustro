package io.github.twinsen81.lustro.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Compiles a small runtime and no-op pair with javac, writes their API dumps with BCV's library, and
 * compares their facades, as `checkFacadeParity` does for each pair of the root build. The tests
 * of Kotlin's own forms, which javac does not write, use the text of a dump.
 */
class FacadeParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "io.github.twinsen81.fixture"

    // A supertype that both halves implement, as the facades of a real pair
    // implement DebugTab from :lustro-api.
    private val shared by lazy {
        compileJava(
            tmp,
            "shared",
            sources = listOf(
                "Base" to """
                    package $pkg.api;
                    public interface Base {
                        default String id() { return "base"; }
                    }
                """,
            ),
        )
    }

    private val runtimeWidget = """
        package $pkg;
        public final class Widget implements $pkg.api.Base {
            private final String name;
            public Widget(String name) { this.name = name; }
            public static Widget create(String name) { return new Widget(name); }
            public String getName() { return name; }
            @Override public String id() { return name; }
            private void log() {}
        }
    """

    private val noopWidget = """
        package $pkg;
        public final class Widget implements $pkg.api.Base {
            public Widget(String name) {}
            public static Widget create(String name) { return new Widget(name); }
            public String getName() { return ""; }
        }
    """

    @Test
    fun `a pair that differs only in implementation passes`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget, "Engine" to "package $pkg; class Engine {}")
        val noop = compile("noop", "Widget" to noopWidget)

        assertNull(compare(runtime, noop))
    }

    @Test
    fun `a public member on one side only fails`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget)
        val noop = compile(
            "noop",
            "Widget" to noopWidget.replace("public String getName()", "public void reset() {}\n public String getName()"),
        )

        val report = compare(runtime, noop)

        assertNotNull(report)
        assertTrue(report!!, report.contains("Signatures only in :fixture-noop (missing from :fixture):"))
        assertTrue(report, report.contains("  + $pkg.Widget :: public fun reset ()V"))
        assertFalse(report, report.contains("Signatures only in :fixture (missing"))
    }

    @Test
    fun `a public type on one side only fails`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget, "Options" to "package $pkg; public final class Options {}")
        val noop = compile("noop", "Widget" to noopWidget)

        val report = compare(runtime, noop)

        assertNotNull(report)
        assertTrue(report!!, report.contains("Public TYPES only in :fixture (missing from :fixture-noop):"))
        assertTrue(report, report.contains("  + $pkg.Options"))
    }

    @Test
    fun `a constructor that differs fails`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget)
        val noop = compile(
            "noop",
            "Widget" to noopWidget
                .replace("public Widget(String name) {}", "public Widget() {}")
                .replace("new Widget(name)", "new Widget()"),
        )

        val report = compare(runtime, noop)

        assertNotNull(report)
        assertTrue(report!!, report.contains("  + $pkg.Widget :: public fun <init> (Ljava/lang/String;)V"))
        assertTrue(report, report.contains("  + $pkg.Widget :: public fun <init> ()V"))
    }

    @Test
    fun `a protected member on one side only fails`() {
        val panel = "package $pkg; public class Panel { public void show() {} }"
        val runtime = compile(
            "runtime",
            "Widget" to runtimeWidget,
            "Panel" to panel.replace("public void show() {}", "public void show() {} protected void refresh() {}"),
        )
        val noop = compile("noop", "Widget" to noopWidget, "Panel" to panel)

        val report = compare(runtime, noop)

        assertNotNull(report)
        assertTrue(report!!, report.contains("  + $pkg.Panel :: protected fun refresh ()V"))
    }

    @Test
    fun `a public member that comes from an interface that is not public fails`() {
        // A package-private interface is not public, as an internal Kotlin interface is not.
        val runtime = compile(
            "runtime",
            "Hidden" to "package $pkg; interface Hidden { String secret(); }",
            "Widget" to runtimeWidget
                .replace("implements $pkg.api.Base", "implements $pkg.api.Base, Hidden")
                .replace("private void log() {}", "public String secret() { return name; }"),
        )
        val noop = compile("noop", "Widget" to noopWidget)

        val report = compare(runtime, noop)

        assertNotNull(report)
        assertTrue(report!!, report.contains("  + $pkg.Widget :: public fun secret ()Ljava/lang/String;"))
    }

    @Test
    fun `a subpackage counts only when it is listed`() {
        val runtime = compile(
            "runtime",
            "Widget" to runtimeWidget,
            "Extra" to "package $pkg.extra; public final class Extra { public void more() {} }",
        )
        val noop = compile("noop", "Widget" to noopWidget)

        assertNull(compare(runtime, noop))
        val report = compare(runtime, noop, listOf(pkg, "$pkg.extra"))
        assertNotNull(report)
        assertTrue(report!!, report.contains("  + $pkg.extra.Extra"))
    }

    @Test
    fun `an empty facade fails instead of passing`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget)
        val noop = compile("noop", "Widget" to noopWidget)

        val report = compare(runtime, noop, listOf("io.github.twinsen81.missing"))

        assertNotNull(report)
        assertTrue(report!!, report.contains("an empty facade type set"))
    }

    @Test
    fun `the facade lists the public types, their headers, and their own members`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget)

        val facade = readFacade(parseApiDump(apiDump(runtime), "runtime"), sharedDump(), listOf(pkg))

        val type = "io/github/twinsen81/fixture/Widget"
        assertEquals(setOf("$pkg.Widget"), facade.types)
        assertEquals(
            setOf(
                "$pkg.Widget :: public final class $type : io/github/twinsen81/fixture/api/Base",
                "$pkg.Widget :: public fun <init> (Ljava/lang/String;)V",
                "$pkg.Widget :: public static fun create (Ljava/lang/String;)L$type;",
                "$pkg.Widget :: public fun getName ()Ljava/lang/String;",
            ),
            facade.signatures,
        )
    }

    @Test
    fun `the accessor of a private constructor is left out, and a constructor with default arguments is not`() {
        val marker = "Lkotlin/jvm/internal/DefaultConstructorMarker;"
        val runtime = """
            public final class a/Tab {
            	public fun <init> (Ljava/lang/String;)V
            	public synthetic fun <init> (Ljava/lang/String;I$marker)V
            	public synthetic fun <init> (La/internal/Store;$marker)V
            }
        """.trimIndent()
        val noop = """
            public final class a/Tab {
            	public fun <init> (Ljava/lang/String;)V
            	public synthetic fun <init> (Ljava/lang/String;I$marker)V
            }
        """.trimIndent()

        assertNull(compareDumps(runtime, noop, listOf("a")))
        val noDefaults = noop.lines().filterNot { "synthetic" in it }.joinToString("\n")
        val report = compareDumps(runtime, noDefaults, listOf("a"))
        assertNotNull(report)
        assertTrue(report!!, report.contains("  + a.Tab :: public synthetic fun <init> (Ljava/lang/String;I$marker)V"))
    }

    @Test
    fun `an override of a public supertype's member is left out only with the same visibility`() {
        val shared = """
            public abstract class a/Base {
            	public fun <init> ()V
            	protected fun onStart ()V
            	public fun getId ()Ljava/lang/String;
            }
        """.trimIndent()
        val noop = """
            public final class b/Tab : a/Base {
            	public fun <init> ()V
            }
        """.trimIndent()
        val runtime = noop.replace(
            "public fun <init> ()V",
            "public fun <init> ()V\n\tprotected fun onStart ()V\n\tpublic final fun getId ()Ljava/lang/String;",
        )

        assertNull(compareDumps(runtime, noop, listOf("b"), shared))
        val widened = runtime.replace("protected fun onStart", "public fun onStart")
        val report = compareDumps(widened, noop, listOf("b"), shared)
        assertNotNull(report)
        assertTrue(report!!, report.contains("  + b.Tab :: public fun onStart ()V"))
    }

    private fun compare(runtime: File, noop: File, packages: List<String> = listOf(pkg)): String? =
        compareDumps(apiDump(runtime), apiDump(noop), packages, apiDump(shared))

    private fun compareDumps(runtime: String, noop: String, packages: List<String>, shared: String = ""): String? {
        val sharedClasses = parseApiDump(shared, "shared")
        return compareFacades(
            ":fixture",
            ":fixture-noop",
            readFacade(parseApiDump(runtime, "runtime"), sharedClasses, packages),
            readFacade(parseApiDump(noop, "noop"), sharedClasses, packages),
        )
    }

    private fun sharedDump() = parseApiDump(apiDump(shared), "shared")

    private fun compile(name: String, vararg sources: Pair<String, String>): File =
        compileJava(tmp, name, sources.toList(), classpath = listOf(shared))
}
