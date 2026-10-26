package io.github.twinsen81.lustro.prefs.internal

import android.content.SharedPreferences
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File

/**
 * The files of the Preferences tab: the registered ones, and, with a
 * [directory], the other files in it, which it opens with [open] each time
 * it lists or finds them. [open] returns the platform's instance of a file.
 */
internal class PrefsRegistry(
    files: List<PrefsFile>,
    private val directory: File?,
    private val discoveredReadOnly: Boolean,
    private val open: (String) -> SharedPreferences,
) {
    private val registered: Map<String, PrefsSource> =
        files.associateTo(LinkedHashMap()) { file ->
            file.name to PrefsSource(file.name, file.prefs, file.policy, file.readOnly, registered = true) { open(file.name) }
        }

    private val lock = Any()

    // Guarded by lock. A discovered file stays here while the platform keeps the
    // same instance of it, so that its sequence and epoch stay too.
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

    // Context.deleteSharedPreferences drops the platform's instance, and the app then
    // gets a new one. The old instance keeps what the file held before: the tab would
    // show it, and an edit would write it back over the file. So a new instance gets a
    // new source, whose new epoch makes a client read the file again.
    private fun discovered(name: String): PrefsSource =
        synchronized(lock) {
            val prefs = open(name)
            discovered[name]?.takeIf { it.prefs === prefs }
                ?: PrefsSource(name, prefs, ValuePolicy.MASK_SENSITIVE, discoveredReadOnly, registered = false)
                    .also { source ->
                        discovered.put(name, source)?.unlisten()
                        if (started) source.listen()
                    }
        }

    private companion object {
        const val XML = ".xml"
    }
}
