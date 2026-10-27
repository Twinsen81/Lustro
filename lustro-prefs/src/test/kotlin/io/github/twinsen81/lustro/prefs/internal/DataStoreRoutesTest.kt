package io.github.twinsen81.lustro.prefs.internal

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.prefs.PrefsDataStore
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Preferences routes over real Preferences DataStores: registered ones, and files of ones the app does not register. */
@RunWith(RobolectricTestRunner::class)
class DataStoreRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val directory = File(app.dataDir, "files/datastore")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appState: DataStore<Preferences>
    private var registry: PrefsRegistry? = null

    @Before
    fun setUp() {
        appState = store("app_state")
        edit(appState) {
            it[stringPreferencesKey("theme")] = "dark"
            it[stringPreferencesKey("auth_token")] = "t0k3n"
            it[intPreferencesKey("launch_count")] = 3
            it[doublePreferencesKey("zoom")] = 1.25
            it[byteArrayPreferencesKey("avatar_hash")] = byteArrayOf(0, 1, 2, -1)
        }
    }

    @After
    fun tearDown() {
        registry?.stop()
        scope.cancel()
    }

    @Test
    fun `files lists a registered store after the SharedPreferences files, then a store it found on disk`() {
        val sdk = store("sdk_state")
        edit(sdk) { it[stringPreferencesKey("install_id")] = "i-9" }
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("username", "ann").commit()
        val routes = routes(PrefsDataStore("app_state", appState), files = listOf(PrefsFile("settings", settings)))

        val items = get(routes, "files").getJSONArray("items")
        val listed = (0 until items.length()).map { items.getJSONObject(it) }
        assertEquals(listOf("settings", "app_state", "sdk_state"), listed.map { it.getString("name") })
        assertEquals(listOf("shared_prefs", "preferences_datastore", "preferences_datastore"), listed.map { it.getString("store") })

        val registered = listed[1]
        assertTrue(registered.getBoolean("registered"))
        assertEquals("mask_sensitive", registered.getString("policy"))
        assertFalse(registered.getBoolean("readOnly"))
        assertFalse(registered.getBoolean("encrypted"))
        assertEquals(5, registered.getInt("keyCount"))

        val discovered = listed[2]
        assertFalse(discovered.getBoolean("registered"))
        assertEquals("mask_sensitive", discovered.getString("policy"))
        assertTrue(discovered.getBoolean("readOnly"))
        assertEquals(1, discovered.getInt("keyCount"))
    }

    @Test
    fun `entries shows every type of a registered store, after its policy`() {
        val routes = routes(PrefsDataStore("app_state", appState))
        val entries = get(routes, "entries", "store" to STORE, "file" to "app_state")
        assertEquals("reset", entries.getString("status"))
        assertEquals("dark", item(entries, "theme").getString("value"))
        assertEquals("int", item(entries, "launch_count").getString("type"))
        assertEquals("double", item(entries, "zoom").getString("type"))
        assertEquals(1.25, item(entries, "zoom").getDouble("value"), 0.0)
        assertEquals(8, item(entries, "zoom").getInt("bytes"))
        assertEquals("bytes", item(entries, "avatar_hash").getString("type"))
        assertEquals("AAEC/w==", item(entries, "avatar_hash").getString("value"))
        assertEquals(4, item(entries, "avatar_hash").getInt("bytes"))
        assertTrue(item(entries, "auth_token").getBoolean("masked"))
        assertFalse(item(entries, "auth_token").has("value"))
    }

    @Test
    fun `edit writes every type through the app's instance`() {
        val routes = routes(PrefsDataStore("app_state", appState, ValuePolicy.VISIBLE))
        val cases =
            listOf(
                Triple("string", "\"bob\"", "bob"),
                Triple("int", "\"7\"", 7),
                Triple("long", "9007199254740993", 9_007_199_254_740_993L),
                Triple("float", "0.5", 0.5f),
                Triple("boolean", "true", true),
                Triple("string_set", "[\"b\",\"a\"]", setOf("a", "b")),
                Triple("double", "0.1", 0.1),
            )
        for ((type, value, stored) in cases) {
            val response = post(routes, "edit", """{"store":"$STORE","file":"app_state","key":"k_$type","type":"$type","value":$value}""")
            assertEquals(response.text(), 200, response.status)
            assertEquals(type, JSONObject(response.text()).getJSONObject("item").getString("type"))
            assertEquals(type, stored, data(appState)["k_$type"])
        }
        val bytes = post(routes, "edit", """{"store":"$STORE","file":"app_state","key":"k_bytes","type":"bytes","value":"AAEC/w=="}""")
        assertEquals(bytes.text(), 200, bytes.status)
        assertEquals("AAEC/w==", JSONObject(bytes.text()).getJSONObject("item").getString("value"))
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), data(appState)["k_bytes"] as ByteArray)
    }

    @Test
    fun `delete and clear go through the app's instance, and move the cursor`() {
        val routes = routes(PrefsDataStore("app_state", appState))
        var cursor = get(routes, "entries", "store" to STORE, "file" to "app_state").getString("cursor")

        assertTrue(JSONObject(post(routes, "delete", """{"store":"$STORE","file":"app_state","key":"theme"}""").text()).getBoolean("deleted"))
        assertNull(data(appState)["theme"])
        val delta = get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor)
        assertEquals("delta", delta.getString("status"))
        cursor = delta.getString("cursor")

        val cleared = post(routes, "clear", """{"store":"$STORE","file":"app_state","confirm":true}""")
        assertEquals(4, JSONObject(cleared.text()).getInt("removed"))
        assertTrue(data(appState).isEmpty())
        assertEquals("delta", get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `an edit that changes nothing leaves the cursor, also for the same bytes`() {
        val routes = routes(PrefsDataStore("app_state", appState))
        val cursor = get(routes, "entries", "store" to STORE, "file" to "app_state").getString("cursor")
        post(routes, "edit", """{"store":"$STORE","file":"app_state","key":"avatar_hash","type":"bytes","value":"AAEC/w=="}""")
        post(routes, "edit", """{"store":"$STORE","file":"app_state","key":"zoom","type":"double","value":1.25}""")
        assertEquals("unchanged", get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `an app change moves the cursor while started, and one made while stopped shows when it starts again`() {
        val routes = routes(PrefsDataStore("app_state", appState))
        registry!!.start()
        val cursor = get(routes, "entries", "store" to STORE, "file" to "app_state").getString("cursor")

        edit(appState) { it[intPreferencesKey("launch_count")] = 4 }
        val delta = eventually { get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor) }
        assertEquals(4, item(delta, "launch_count").getInt("value"))

        registry!!.stop()
        val stopped = delta.getString("cursor")
        edit(appState) { it[intPreferencesKey("launch_count")] = 5 }
        Thread.sleep(SETTLE_MS)
        assertEquals("unchanged", get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to stopped).getString("status"))

        registry!!.start()
        assertEquals(5, item(eventually { get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to stopped) }, "launch_count").getInt("value"))
    }

    @Test
    fun `an app change before the tab's first collection still moves the cursor of a client that read before it`() {
        // The collection that follows the data starts on another thread; here it starts only after the change.
        val late = LateStore(appState)
        val routes = routes(PrefsDataStore("app_state", late))
        registry!!.start()
        val cursor = get(routes, "entries", "store" to STORE, "file" to "app_state").getString("cursor")

        edit(appState) { it[intPreferencesKey("launch_count")] = 4 }
        late.collect.countDown()

        val delta = eventually { get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor) }
        assertEquals(4, item(delta, "launch_count").getInt("value"))
    }

    @Test
    fun `after the store's data failed, a read that succeeds follows the data again`() {
        val flaky = FlakyStore(appState)
        val routes = routes(PrefsDataStore("app_state", flaky))
        registry!!.start()
        assertTrue("the store's data failed", flaky.failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        // Let the job that followed the data end.
        Thread.sleep(SETTLE_MS)
        flaky.broken = false

        val cursor = get(routes, "entries", "store" to STORE, "file" to "app_state").getString("cursor")
        edit(appState) { it[intPreferencesKey("launch_count")] = 4 }

        val delta = eventually { get(routes, "entries", "store" to STORE, "file" to "app_state", "cursor" to cursor) }
        assertEquals(4, item(delta, "launch_count").getInt("value"))
    }

    @Test
    fun `a read-only registered store refuses edit, delete, and clear with an enveloped 403`() {
        val routes = routes(PrefsDataStore("app_state", appState, ValuePolicy.VISIBLE, readOnly = true))
        for ((route, body) in listOf(
            "edit" to """{"store":"$STORE","file":"app_state","key":"theme","type":"string","value":"x"}""",
            "delete" to """{"store":"$STORE","file":"app_state","key":"theme"}""",
            "clear" to """{"store":"$STORE","file":"app_state","confirm":true}""",
        )) {
            val response = post(routes, route, body)
            assertEquals(route, 403, response.status)
            assertEquals("read_only", JSONObject(response.text()).getString("code"))
        }
        assertEquals("dark", data(appState)["theme"])
    }

    @Test
    fun `a store found on disk is read from its file, and stays read-only when discovered files are writable`() {
        val sdk = store("sdk_state")
        edit(sdk) {
            it[stringPreferencesKey("install_id")] = "i-9"
            it[stringPreferencesKey("api_key")] = "k-9"
            it[doublePreferencesKey("sample_rate")] = 0.5
        }
        val routes = routes(discoveredReadOnly = false)

        val entries = get(routes, "entries", "store" to STORE, "file" to "sdk_state")
        assertEquals("i-9", item(entries, "install_id").getString("value"))
        assertTrue(item(entries, "api_key").getBoolean("masked"))
        assertEquals(0.5, item(entries, "sample_rate").getDouble("value"), 0.0)
        val edit = post(routes, "edit", """{"store":"$STORE","file":"sdk_state","key":"install_id","type":"string","value":"x"}""")
        assertEquals(403, edit.status)
        assertEquals("i-9", data(sdk)["install_id"])

        // Nothing tells the tab of the change: the cursor moves when the file's bytes do.
        val cursor = entries.getString("cursor")
        assertEquals("unchanged", get(routes, "entries", "store" to STORE, "file" to "sdk_state", "cursor" to cursor).getString("status"))
        edit(sdk) { it[stringPreferencesKey("install_id")] = "i-10" }
        val delta = get(routes, "entries", "store" to STORE, "file" to "sdk_state", "cursor" to cursor)
        assertEquals("delta", delta.getString("status"))
        assertEquals("i-10", item(delta, "install_id").getString("value"))
    }

    @Test
    fun `the registered store's own file is not listed again, and a file that is not a store is listed with the reason`() {
        File(directory, "junk.preferences_pb").writeText("not a protobuf message")
        File(directory, "notes.txt").writeText("not a store")
        val routes = routes(PrefsDataStore("app_state", appState))

        val items = get(routes, "files").getJSONArray("items")
        val listed = (0 until items.length()).map { items.getJSONObject(it) }
        assertEquals(listOf("app_state", "junk"), listed.map { it.getString("name") })
        assertTrue(listed[1].getString("error"), listed[1].getString("error").contains("not a Preferences DataStore file"))
        assertEquals(0, listed[1].getInt("keyCount"))
        assertEquals(500, call(routes, "GET", "entries", "store" to STORE, "file" to "junk").status)
    }

    @Test
    fun `a registered store that can't read its file is listed with the reason, and entries is a 500`() {
        directory.mkdirs()
        File(directory, "broken.preferences_pb").writeText("not a protobuf message")
        val routes = routes(PrefsDataStore("broken", store("broken")))
        registry!!.start()
        val broken = get(routes, "files").getJSONArray("items").getJSONObject(0)
        assertTrue(broken.getString("error"), broken.getString("error").contains("Can't read the data store broken"))
        assertEquals(500, call(routes, "GET", "entries", "store" to STORE, "file" to "broken").status)
    }

    @Test
    fun `the store picks the file, so a SharedPreferences file and a store can have the same name`() {
        val prefs = app.getSharedPreferences("app_state", Context.MODE_PRIVATE)
        prefs.edit().putString("legacy", "1").commit()
        val routes = routes(PrefsDataStore("app_state", appState), files = listOf(PrefsFile("app_state", prefs)))

        assertEquals(listOf("legacy"), keys(get(routes, "entries", "file" to "app_state")))
        assertEquals(listOf("legacy"), keys(get(routes, "entries", "store" to "shared_prefs", "file" to "app_state")))
        assertEquals(5, keys(get(routes, "entries", "store" to STORE, "file" to "app_state")).size)

        assertEquals(200, post(routes, "edit", """{"file":"app_state","key":"k","type":"string","value":"v"}""").status)
        assertEquals("v", prefs.getString("k", null))
        assertNull(data(appState)["k"])
    }

    @Test
    fun `a store that is not one of the two, or a file of the wrong store, is refused`() {
        val routes = routes(PrefsDataStore("app_state", appState))
        val unknown = call(routes, "GET", "entries", "store" to "proto", "file" to "app_state")
        assertEquals(400, unknown.status)
        assertEquals("store", JSONObject(unknown.text()).getString("field"))
        val notString = post(routes, "edit", """{"store":5,"file":"app_state","key":"k","type":"string","value":"v"}""")
        assertEquals("store", JSONObject(notString.text()).getString("field"))
        val missing = call(routes, "GET", "entries", "file" to "app_state")
        assertEquals(404, missing.status)
        assertEquals("file", JSONObject(missing.text()).getString("field"))
    }

    @Test
    fun `a SharedPreferences file takes no double and no bytes, and a store takes both`() {
        val settings = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val routes = routes(PrefsDataStore("app_state", appState), files = listOf(PrefsFile("settings", settings)))
        for (type in listOf("double", "bytes")) {
            val response = post(routes, "edit", """{"file":"settings","key":"k","type":"$type","value":"AAEC"}""")
            assertEquals(type, 400, response.status)
            val error = JSONObject(response.text())
            assertEquals("type", error.getString("field"))
            assertTrue(error.getString("message"), error.getString("message").contains("SharedPreferences"))
        }
        assertEquals(200, post(routes, "edit", """{"store":"$STORE","file":"app_state","key":"k","type":"double","value":"2"}""").status)
        assertEquals(2.0, data(appState)["k"])
    }

    // A store whose data fails while it is broken, as after an IOException or a migration
    // that threw. DataStore reads its file again when its data is collected again.
    private class FlakyStore(private val store: DataStore<Preferences>) : DataStore<Preferences> {
        @Volatile
        var broken = true
        val failed = CountDownLatch(1)

        override val data: Flow<Preferences> =
            flow {
                if (broken) {
                    failed.countDown()
                    throw IOException("the disk is busy")
                }
                emitAll(store.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = store.updateData(transform)
    }

    // A store whose data starts to emit on the dispatcher's threads only after collect opens: the
    // tab's collection runs there, and a route's read on the calling thread is not held.
    private class LateStore(private val store: DataStore<Preferences>) : DataStore<Preferences> {
        val collect = CountDownLatch(1)

        override val data: Flow<Preferences> =
            flow {
                if (Thread.currentThread().name.startsWith("DefaultDispatcher")) collect.await()
                emitAll(store.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = store.updateData(transform)
    }

    private fun store(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { File(directory, "$name.preferences_pb") }

    private fun edit(store: DataStore<Preferences>, change: (MutablePreferences) -> Unit) {
        runBlocking { store.edit { change(it) } }
    }

    private fun data(store: DataStore<Preferences>): Map<String, Any> = runBlocking { store.data.first().asMap().mapKeys { it.key.name } }

    private fun routes(
        vararg dataStores: PrefsDataStore,
        files: List<PrefsFile> = emptyList(),
        discoveredReadOnly: Boolean = true,
    ): PrefsRoutes {
        registry?.stop()
        val created =
            PrefsRegistry(
                files,
                File(app.dataDir, "shared_prefs"),
                discoveredReadOnly,
                dataStores.toList(),
                directory,
            ) { app.getSharedPreferences(it, Context.MODE_PRIVATE) }
        registry = created
        return PrefsRoutes(created)
    }

    // The store follows its data on another thread, so a change shows a moment later.
    private fun eventually(poll: () -> JSONObject): JSONObject {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            val envelope = poll()
            if (envelope.getString("status") == "delta" || System.currentTimeMillis() > deadline) {
                assertEquals("delta", envelope.getString("status"))
                return envelope
            }
            Thread.sleep(SETTLE_MS / 10)
        }
    }

    private fun call(routes: PrefsRoutes, method: String, path: String, vararg query: Pair<String, String>, body: String? = null): DebugResponse =
        routes.handle(DebugRequest(path, method, query.associate { it.first to listOf(it.second) }, body = body?.toByteArray()))!!

    private fun get(routes: PrefsRoutes, path: String, vararg query: Pair<String, String>): JSONObject {
        val response = call(routes, "GET", path, *query)
        assertEquals(response.text(), 200, response.status)
        return JSONObject(response.text())
    }

    private fun post(routes: PrefsRoutes, path: String, body: String): DebugResponse = call(routes, "POST", path, body = body)

    private fun keys(envelope: JSONObject): List<String> {
        val items = envelope.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("key") }
    }

    private fun item(envelope: JSONObject, key: String): JSONObject {
        val items = envelope.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("key") == key }
    }

    private companion object {
        const val STORE = "preferences_datastore"
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
    }
}
