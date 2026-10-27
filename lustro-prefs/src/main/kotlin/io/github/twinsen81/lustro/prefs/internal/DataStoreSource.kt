package io.github.twinsen81.lustro.prefs.internal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking

/**
 * A Preferences DataStore that the app registered: the tab reads and writes
 * through the app's own instance. Only this class calls DataStore and
 * coroutines, which are `compileOnly`, so an app without them never loads it.
 * The routes' threads may block, so the calls wait with `runBlocking`.
 */
internal class DataStoreSource(
    name: String,
    private val dataStore: DataStore<Preferences>,
    policy: ValuePolicy,
    readOnly: Boolean,
) : PrefsSource(name, PrefsStore.PREFERENCES_DATASTORE, policy, readOnly, registered = true) {
    // Guarded by this. The scope that follows the store's data, while the tab listens.
    private var scope: CoroutineScope? = null

    // Guarded by this. The job that follows the store's data, which a failed read ends.
    private var following: Job? = null

    // The data that the tab last saw, which stays from stop() to start(), so that a
    // change made between them moves the cursor when the tab listens again.
    private val seen = AtomicReference<Preferences?>(null)

    /**
     * Follows the store's data, which emits once now and again after each change.
     * A failed read ends it: [read] says why, and the next read that succeeds
     * follows the data again.
     */
    @Synchronized
    override fun listen() {
        if (scope != null) return
        val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = created
        follow(created)
    }

    @Synchronized
    override fun unlisten() {
        scope?.cancel()
        scope = null
        following = null
    }

    // Guarded by this.
    private fun follow(scope: CoroutineScope) {
        following =
            dataStore.data
                .onEach { data ->
                    val before = seen.getAndSet(data)
                    if (before != null && before != data) changed()
                }
                // Without it, the failure would reach the app's uncaught exception handler.
                .catch { }
                .launchIn(scope)
    }

    override fun read(): Contents {
        val data =
            try {
                runBlocking { dataStore.data.first() }
            } catch (e: Exception) {
                throw UnreadableFileException("Can't read the data store $name: $e", e)
            }
        // The job starts to collect on another thread, so its first data can come after a change
        // that the app made since this read. What a client was given is what the job compares
        // with, until the job has data of its own.
        seen.compareAndSet(null, data)
        followAgain()
        return Contents(data.asMap().mapKeys { it.key.name }, encrypted = false, policy = policy, readOnly = readOnly)
    }

    // A store reads its file again after a failed read, such as an IOException or a migration
    // that threw, so a read can succeed after the store's data failed and ended the job. Without
    // a new one, the app's changes would no longer move the cursor.
    @Synchronized
    private fun followAgain() {
        val listening = scope ?: return
        if (following?.isActive == true) return
        follow(listening)
    }

    override fun writable(): Boolean = !readOnly

    // The store writes the file, then updates its data, so a change that fails leaves both as they were.
    override fun write(change: PrefChange) {
        try {
            runBlocking { dataStore.updateData { data -> data.toMutablePreferences().apply { make(change) } } }
        } catch (e: Exception) {
            throw WriteFailedException("Can't write the data store $name: $e", e)
        }
    }

    private fun MutablePreferences.make(change: PrefChange) {
        when (change) {
            is PrefChange.Put -> this[key(change.key)] = change.value
            is PrefChange.Remove -> remove(key(change.key))
            PrefChange.Clear -> clear()
        }
    }

    // Every key factory makes a Preferences.Key of the name, keys are equal by name, and
    // the store writes a value by its class. So one factory serves every type, also the
    // byte array of DataStore 1.1, which DataStore 1.0 has no factory for and refuses to write.
    @Suppress("UNCHECKED_CAST")
    private fun key(name: String): Preferences.Key<Any> = stringPreferencesKey(name) as Preferences.Key<Any>
}
