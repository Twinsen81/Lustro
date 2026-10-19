package io.github.twinsen81.lustro.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.tools.ToolProvider

/**
 * Compiles a small runtime and no-op pair with javac and compares their facades, as
 * `checkFacadeParity` does for each pair of the root build.
 */
class FacadeParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "io.github.twinsen81.fixture"

    // A supertype that both halves implement, as the facades of a real pair
    // implement DebugTab from :lustro-api.
    private val shared by lazy {
        compile(
            "shared",
            "Base" to """
                package $pkg.api;
                public interface Base {
                    default String id() { return "base"; }
                }
            """,
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
            public Widget() {}
            public static Widget create(String name) { return new Widget(); }
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
        assertTrue(report!!, report.contains("Members only in :fixture-noop (missing from :fixture):"))
        assertTrue(report, report.contains("  + $pkg.Widget :: void reset()"))
        assertTrue(report, !report.contains("Members only in :fixture (missing"))
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
    fun `the facade lists the public types and their own members`() {
        val runtime = compile("runtime", "Widget" to runtimeWidget)

        val facade = reader().read(runtime, listOf(pkg))

        assertEquals(setOf("$pkg.Widget"), facade.types)
        assertEquals(
            setOf("$pkg.Widget :: $pkg.Widget create(java.lang.String);", "$pkg.Widget :: java.lang.String getName();"),
            facade.signatures,
        )
    }

    private fun compare(runtime: File, noop: File, packages: List<String> = listOf(pkg)): String? {
        val reader = reader()
        return compareFacades(":fixture", ":fixture-noop", reader.read(runtime, packages), reader.read(noop, packages))
    }

    private fun reader() = FacadeReader(File(System.getProperty("java.home"), "bin/javap").absolutePath, listOf(shared))

    /** Compiles the Java [sources], each a simple name and its text, into a new directory. */
    private fun compile(name: String, vararg sources: Pair<String, String>): File {
        val srcDir = tmp.newFolder("$name-src")
        val outDir = tmp.newFolder("$name-classes")
        val files = sources.map { (simpleName, text) ->
            val packageName = Regex("""package\s+([\w.]+);""").find(text)!!.groupValues[1]
            File(srcDir, "${packageName.replace('.', '/')}/$simpleName.java").apply {
                parentFile.mkdirs()
                writeText(text.trimIndent())
            }
        }
        val classpath = if (name == "shared") emptyList() else listOf("-classpath", shared.path)
        val args = classpath + listOf("-d", outDir.path) + files.map { it.path }
        val status = ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray())
        assertEquals("javac failed for $name", 0, status)
        return outDir
    }
}
