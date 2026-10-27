package io.github.twinsen81.lustro.prefs.internal

import android.content.SharedPreferences
import io.github.twinsen81.lustro.prefs.PrefsDataStore
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File

/**
 * The files of the Preferences tab: the registered SharedPreferences files and
 * Preferences DataStores, and, with a [directory] and a [dataStoreDirectory],
 * the other files in them. It opens a discovered SharedPreferences file with
 * [open] each time it lists or finds it; [open] returns the platform's instance
 * of a file. It reads a discovered DataStore file itself.
 */
internal class PrefsRegistry(
    files: List<PrefsFile>,
    private val directory: File?,
    private val discoveredReadOnly: Boolean,
    dataStores: List<PrefsDataStore> = emptyList(),
    private val dataStoreDirectory: File? = null,
    private val open: (String) -> SharedPreferences,
) {
    private val registered: Map<String, PrefsSource> =
        files.associateTo(LinkedHashMap()) { file ->
            file.name to SharedPrefsSource(file.name, file.prefs, file.policy, file.readOnly, registered = true) { open(file.name) }
        }

    private val registeredDataStores: Map<String, PrefsSource> =
        dataStores.associateTo(LinkedHashMap()) { store ->
            store.name to DataStoreSource(store.name, store.dataStore, store.policy, store.readOnly)
        }

    private val lock = Any()

    // Guarded by lock. A discovered file stays here while the platform keeps the
    // same instance of it, so that its sequence and epoch stay too.
    private val discovered = HashMap<String, SharedPrefsSource>()

    // Guarded by lock. A discovered DataStore file stays here, so that its sequence and epoch stay too.
    private val discoveredDataStores = HashMap<String, DataStoreFileSource>()
    private var started = false

    /**
     * The registered SharedPreferences files and DataStores, in their order, then
     * the other files on disk, each kind by name.
     */
    fun files(): List<PrefsSource> =
        registered.values + registeredDataStores.values +
            onDisk().sorted().map { discovered(it) } +
            dataStoresOnDisk().sorted().map { discoveredDataStore(it) }

    /**
     * The file of [store] named [name]: a registered one, or a discovered one that
     * is on disk. A name that is neither gets `null`, so that a request can't make
     * the platform create a file.
     */
    fun find(store: PrefsStore, name: String): PrefsSource? =
        when (store) {
            PrefsStore.SHARED_PREFS ->
                registered[name] ?: if (directory != null && File(directory, name + XML).isFile) discovered(name) else null
            PrefsStore.PREFERENCES_DATASTORE ->
                registeredDataStores[name]
                    ?: if (dataStoreDirectory != null && File(dataStoreDirectory, name + PREFERENCES_PB).isFile) discoveredDataStore(name) else null
        }

    /** Follows the files that the tab has opened, and the ones it opens later. */
    fun start() {
        synchronized(lock) {
            started = true
            registered.values.forEach { it.listen() }
            registeredDataStores.values.forEach { it.listen() }
            discovered.values.forEach { it.listen() }
        }
    }

    fun stop() {
        synchronized(lock) {
            started = false
            registered.values.forEach { it.unlisten() }
            registeredDataStores.values.forEach { it.unlisten() }
            discovered.values.forEach { it.unlisten() }
        }
    }

    private fun onDisk(): List<String> = namesIn(directory, XML).filter { it !in registered }

    // DataStore writes a .tmp file next to the store's file first, which the suffix leaves out.
    private fun dataStoresOnDisk(): List<String> = namesIn(dataStoreDirectory, PREFERENCES_PB).filter { it !in registeredDataStores }

    private fun namesIn(directory: File?, suffix: String): List<String> =
        directory?.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(suffix) }
            .map { it.name.removeSuffix(suffix) }
            .filter { it.isNotEmpty() }

    // Context.deleteSharedPreferences drops the platform's instance, and the app then
    // gets a new one. The old instance keeps what the file held before: the tab would
    // show it, and an edit would write it back over the file. So a new instance gets a
    // new source, whose new epoch makes a client read the file again.
    private fun discovered(name: String): PrefsSource =
        synchronized(lock) {
            val prefs = open(name)
            discovered[name]?.takeIf { it.prefs === prefs }
                ?: SharedPrefsSource(name, prefs, ValuePolicy.MASK_SENSITIVE, discoveredReadOnly || name == LUSTRO_FILE, registered = false)
                    .also { source ->
                        discovered.put(name, source)?.unlisten()
                        if (started) source.listen()
                    }
        }

    private fun discoveredDataStore(name: String): PrefsSource =
        synchronized(lock) {
            discoveredDataStores.getOrPut(name) { DataStoreFileSource(name, File(dataStoreDirectory, name + PREFERENCES_PB)) }
        }

    private companion object {
        const val XML = ".xml"

        // The extension that preferencesDataStore gives the file of a store.
        const val PREFERENCES_PB = ".preferences_pb"

        // Lustro's own file. The server checks its token on every request, so an edit
        // or a clear of it would lock the client out.
        const val LUSTRO_FILE = "lustro_debug"
    }
}
