package io.github.twinsen81.lustro.gradle

import kotlinx.validation.KotlinApiCompareTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The `apiCheck` of a module: BCV's compare task, with the committed baseline and the dump that
 * `apiBuild` writes of the module's classes, here made with BCV's library from javac fixtures.
 */
class ApiBaselineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val widget = """
        package io.github.twinsen81.fixture;
        public final class Widget {
            public Widget(String name) {}
            public String getName() { return ""; }
        }
    """

    @Test
    fun `the check passes when the API is the baseline`() {
        val baseline = dumpOf("v1", widget)

        assertNull(check(baseline = baseline, generated = dumpOf("v2", widget.replace("return \"\";", "return \"x\";"))))
    }

    @Test
    fun `a change to a public constructor fails the check`() {
        val baseline = dumpOf("v1", widget)
        val changed = dumpOf("v2", widget.replace("public Widget(String name) {}", "public Widget(String name, int size) {}"))

        val failure = check(baseline = baseline, generated = changed)

        assertNotNull(failure)
        assertTrue(failure!!, failure.contains("API check failed for project"))
        assertTrue(failure, failure.contains("-\tpublic fun <init> (Ljava/lang/String;)V"))
        assertTrue(failure, failure.contains("+\tpublic fun <init> (Ljava/lang/String;I)V"))
    }

    private fun dumpOf(name: String, source: String): File =
        tmp.newFile("$name.api").apply { writeText(apiDump(compileJava(tmp, name, listOf("Widget" to source)))) }

    /** Runs the compare task as `apiCheck` configures it; returns its failure message, or null when it passes. */
    private fun check(baseline: File, generated: File): String? {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        val task = project.tasks.register("apiCheck", KotlinApiCompareTask::class.java) {
            projectApiFile.set(baseline)
            generatedApiFile.set(generated)
        }.get()
        return runCatching { task.actions.forEach { it.execute(task) } }.exceptionOrNull()?.let { error ->
            generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        }
    }
}
