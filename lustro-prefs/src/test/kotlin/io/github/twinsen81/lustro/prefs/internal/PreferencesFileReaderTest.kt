package io.github.twinsen81.lustro.prefs.internal

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The reader of a Preferences DataStore file, over files that DataStore itself wrote. */
class PreferencesFileReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `it reads every type as the store's own data has it`() {
        val file = File(folder.root, "app_state.preferences_pb")
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        val data =
            runBlocking {
                store.edit {
                    it[stringPreferencesKey("theme")] = "dark ☾"
                    it[stringPreferencesKey("")] = "empty key"
                    it[intPreferencesKey("count")] = -3
                    it[intPreferencesKey("max")] = Int.MAX_VALUE
                    it[longPreferencesKey("at")] = Long.MIN_VALUE
                    it[floatPreferencesKey("scale")] = 1.25f
                    it[floatPreferencesKey("nan")] = Float.NaN
                    it[doublePreferencesKey("zoom")] = 0.1
                    it[doublePreferencesKey("far")] = Double.NEGATIVE_INFINITY
                    it[booleanPreferencesKey("on")] = true
                    it[booleanPreferencesKey("off")] = false
                    it[stringSetPreferencesKey("tags")] = setOf("work", "travel")
                    it[stringSetPreferencesKey("none")] = emptySet()
                    it[byteArrayPreferencesKey("hash")] = byteArrayOf(0, 1, 2, -1)
                }
                store.data.first().asMap().mapKeys { it.key.name }
            }

        val read = PreferencesFileReader.read(file.readBytes())

        assertEquals(data.keys, read.keys)
        for ((key, value) in data) {
            when (value) {
                is ByteArray -> assertArrayEquals(key, value, read[key] as ByteArray)
                // The store's own set is unmodifiable; any Set<String> equals it.
                is Set<*> -> assertEquals(key, value, read[key])
                else -> {
                    assertEquals(key, value, read[key])
                    assertEquals(key, value::class, read.getValue(key)::class)
                }
            }
        }
    }

    @Test
    fun `an empty file is an empty store`() {
        assertEquals(emptyMap<String, Any>(), PreferencesFileReader.read(ByteArray(0)))
    }

    @Test
    fun `it skips the fields it does not know`() {
        // Field 2 of the map (a varint 7), then entry "a" whose value has field 9
        // (a fixed32) before its string "b".
        val bytes =
            byteArrayOf(0x10, 0x07) +
                byteArrayOf(0x0A, 0x0D, 0x0A, 0x01, 'a'.code.toByte(), 0x12, 0x08) +
                byteArrayOf(0x4D, 1, 2, 3, 4, 0x2A, 0x01, 'b'.code.toByte())
        assertEquals(mapOf("a" to "b"), PreferencesFileReader.read(bytes))
    }

    @Test
    fun `bytes that are not such a file are refused`() {
        val cases =
            mapOf(
                "a length past the end" to byteArrayOf(0x0A, 0x10, 0x0A),
                "a varint that does not end" to ByteArray(11) { 0x80.toByte() },
                "a group, which proto3 has not" to byteArrayOf(0x0B),
                "field number 0" to byteArrayOf(0x02, 0x00),
                "an entry with no value" to byteArrayOf(0x0A, 0x03, 0x0A, 0x01, 'a'.code.toByte()),
                "text" to "not a protobuf message".toByteArray(),
            )
        for ((case, bytes) in cases) {
            try {
                val read = PreferencesFileReader.read(bytes)
                fail("$case read as $read")
            } catch (e: MalformedFileException) {
                assertTrue(case, e.message.orEmpty().isNotEmpty())
            }
        }
    }
}
