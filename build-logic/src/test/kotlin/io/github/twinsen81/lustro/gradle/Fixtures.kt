package io.github.twinsen81.lustro.gradle

import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.junit.Assert.assertEquals
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import javax.tools.ToolProvider

/**
 * Compiles the Java [sources], each a simple name and its text, into a new directory named after
 * [name], with [classpath] on the classpath.
 */
internal fun compileJava(
    tmp: TemporaryFolder,
    name: String,
    sources: List<Pair<String, String>>,
    classpath: List<File> = emptyList(),
): File {
    val srcDir = tmp.newFolder("$name-src")
    val outDir = tmp.newFolder("$name-classes")
    val files = sources.map { (simpleName, text) ->
        val packageName = Regex("""package\s+([\w.]+);""").find(text)!!.groupValues[1]
        File(srcDir, "${packageName.replace('.', '/')}/$simpleName.java").apply {
            parentFile.mkdirs()
            writeText(text.trimIndent())
        }
    }
    val classpathArgs = if (classpath.isEmpty()) emptyList() else listOf("-classpath", classpath.joinToString(File.pathSeparator))
    val args = classpathArgs + listOf("-d", outDir.path) + files.map { it.path }
    val status = ToolProvider.getSystemJavaCompiler().run(null, null, null, *args.toTypedArray())
    assertEquals("javac failed for $name", 0, status)
    return outDir
}

/** The API dump of the classes in [classesDir], made with BCV's library as the `apiBuild` task makes it. */
internal fun apiDump(classesDir: File): String {
    val classFiles = classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
    val streams: List<InputStream> = classFiles.map { it.inputStream() }
    try {
        return buildString { streams.asSequence().loadApiFromJvmClasses().filterOutNonPublic().dump(this) }
    } finally {
        streams.forEach { it.close() }
    }
}
