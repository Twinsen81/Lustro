package io.github.twinsen81.lustro.prefs.internal

import android.content.SharedPreferences
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File

/**
 * The files of the Preferences tab: the registered ones, and, with a
 * [directory], the other files in it, which it opens with [open] the first
 * time it lists them.
 */
internal class PrefsRegistry(
    files: List<PrefsFile>,
    private val directory: File?,
    private val discoveredReadOnly: Boolean,
    private val open: (String) -> SharedPreferences,
) {
    private val registered: Map<String, PrefsSource> =
        files.associateTo(LinkedHashMap()) { it.name to PrefsSource(it.name, it.prefs, it.policy, it.readOnly, registered = true) }

    private val lock = Any()

    // Guarded by lock. A discovered file stays here once opened, as the platform
    // keeps its instance in memory, so that its sequence and epoch stay too.
    private val discovered = HashMap<String, PrefsSource>()
    private var started = false

    /** The registered files, in their order, then the other files on disk, by name. */
    fun files(): List<PrefsSource> = registered.values + onDisk().sorted().map { discovered(it) }

    /**
     * The file named [name]: a registered one, or a discovered one that is on
     * disk. A name that is neither gets `null`, so that a request can't make
     * the platform create a file.
     */
    fun find(name: String): PrefsSource? =
        registered[name] ?: if (directory != null && File(directory, name + XML).isFile) discovered(name) else null

    /** Listens to the files that the tab has opened, and to the ones it opens later. */
    fun start() {
        synchronized(lock) {
            started = true
            registered.values.forEach { it.listen() }
            discovered.values.forEach { it.listen() }
        }
    }

    fun stop() {
        synchronized(lock) {
            started = false
            registered.values.forEach { it.unlisten() }
            discovered.values.forEach { it.unlisten() }
        }
    }

    private fun onDisk(): List<String> =
        directory?.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(XML) }
            .map { it.name.removeSuffix(XML) }
            .filter { it.isNotEmpty() && it !in registered }

    private fun discovered(name: String): PrefsSource =
        synchronized(lock) {
            discovered.getOrPut(name) {
                PrefsSource(name, open(name), ValuePolicy.MASK_SENSITIVE, discoveredReadOnly, registered = false)
                    .also { if (started) it.listen() }
            }
        }

    private companion object {
        const val XML = ".xml"
    }
}
