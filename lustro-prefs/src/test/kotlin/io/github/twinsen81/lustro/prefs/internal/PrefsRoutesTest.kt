package io.github.twinsen81.lustro.prefs.internal

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The Preferences routes over real SharedPreferences files. */
@RunWith(RobolectricTestRunner::class)
class PrefsRoutesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val directory = File(app.dataDir, "shared_prefs")
    private lateinit var settings: SharedPreferences
    private lateinit var session: SharedPreferences
    private var registry: PrefsRegistry? = null

    @Before
    fun setUp() {
        settings = prefs("settings")
        settings.edit().putString("username", "ann").putString("auth_token", "t0k3n").putInt("launch_count", 3).commit()
        session = prefs("session")
        session.edit().putString("session_id", "s-1").putLong("expires_at", 1_792_419_740_010L).commit()
        prefs("sdk_cache").edit().putString("install_id", "i-9").putString("api_key", "k-9").commit()
    }

    @After
    fun tearDown() {
        registry?.stop()
    }

    @Test
    fun `files lists the registered files in their order, then the discovered ones by name`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE), PrefsFile("session", session, ValuePolicy.KEYS_ONLY, readOnly = true))
        val items = get(routes, "files").getJSONArray("items")
        val names = (0 until items.length()).map { items.getJSONObject(it).getString("name") }
        assertEquals(listOf("settings", "session", "sdk_cache"), names)

        val settingsFile = items.getJSONObject(0)
        assertTrue(settingsFile.getBoolean("registered"))
        assertEquals("visible", settingsFile.getString("policy"))
        assertFalse(settingsFile.getBoolean("readOnly"))
        assertFalse(settingsFile.getBoolean("encrypted"))
        assertEquals(3, settingsFile.getInt("keyCount"))
        assertTrue(settingsFile.getString("cursor").isNotEmpty())

        assertEquals("keys_only", items.getJSONObject(1).getString("policy"))
        assertTrue(items.getJSONObject(1).getBoolean("readOnly"))

        val discovered = items.getJSONObject(2)
        assertFalse(discovered.getBoolean("registered"))
        assertEquals("mask_sensitive", discovered.getString("policy"))
        assertTrue("a discovered file is read-only by default", discovered.getBoolean("readOnly"))
        assertEquals(2, discovered.getInt("keyCount"))
    }

    @Test
    fun `without discovery only the registered files are listed and found`() {
        val routes = routes(PrefsFile("settings", settings), discover = false)
        assertEquals(1, get(routes, "files").getJSONArray("items").length())
        assertEquals(404, call(routes, "GET", "entries", "file" to "sdk_cache").status)
    }

    @Test
    fun `the app can make discovered files writable`() {
        val routes = routes(discoveredReadOnly = false)
        val edit = post(routes, "edit", """{"file":"sdk_cache","key":"install_id","type":"string","value":"i-10"}""")
        assertEquals(200, edit.status)
        assertEquals("i-10", prefs("sdk_cache").getString("install_id", null))
    }

    @Test
    fun `Lustro's own file stays read-only when discovered files are writable`() {
        prefs("lustro_debug").edit().putString("lustro_token", "t0k3n").commit()
        val routes = routes(discoveredReadOnly = false)
        assertTrue(file(routes, "lustro_debug").getBoolean("readOnly"))
        assertEquals(403, post(routes, "clear", """{"file":"lustro_debug","confirm":true}""").status)
        assertEquals("t0k3n", prefs("lustro_debug").getString("lustro_token", null))
    }

    @Test
    fun `a discovered file that the app deletes and writes again is read from its new instance`() {
        val routes = routes(discoveredReadOnly = false)
        registry!!.start()
        val cursor = get(routes, "entries", "file" to "sdk_cache").getString("cursor")
        assertTrue(app.deleteSharedPreferences("sdk_cache"))
        prefs("sdk_cache").edit().putString("generation", "2").commit()

        val reset = get(routes, "entries", "file" to "sdk_cache", "cursor" to cursor)
        assertEquals("reset", reset.getString("status"))
        assertEquals(listOf("generation"), keys(reset))

        // An edit through the old instance would write the file's old values back over it.
        assertEquals(200, post(routes, "edit", """{"file":"sdk_cache","key":"install_id","type":"string","value":"i-10"}""").status)
        assertEquals(mapOf("generation" to "2", "install_id" to "i-10"), prefs("sdk_cache").all)
        val onDisk = File(directory, "sdk_cache.xml").readText()
        assertTrue(onDisk, "generation" in onDisk && "api_key" !in onDisk)
    }

    @Test
    fun `entries returns every entry by key, and nothing again until the file changes`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        val reset = get(routes, "entries", "file" to "settings")
        assertEquals("reset", reset.getString("status"))
        assertEquals(listOf("auth_token", "launch_count", "username"), keys(reset))
        assertEquals("t0k3n", item(reset, "auth_token").getString("value"))

        val unchanged = get(routes, "entries", "file" to "settings", "cursor" to reset.getString("cursor"))
        assertEquals("unchanged", unchanged.getString("status"))
        assertFalse(unchanged.has("items"))
        assertEquals(reset.getString("cursor"), unchanged.getString("cursor"))
    }

    @Test
    fun `an app change moves the cursor through the listener while started`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        registry!!.start()
        val cursor = get(routes, "entries", "file" to "settings").getString("cursor")

        settings.edit().putInt("launch_count", 4).apply()
        shadowOf(Looper.getMainLooper()).idle()

        val delta = get(routes, "entries", "file" to "settings", "cursor" to cursor)
        assertEquals("delta", delta.getString("status"))
        assertEquals(4, item(delta, "launch_count").getInt("value"))
        assertEquals(delta.getString("cursor"), file(routes, "settings").getString("cursor"))
    }

    @Test
    fun `after stop the listener is gone, and start brings it back`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        registry!!.start()
        registry!!.stop()
        val cursor = get(routes, "entries", "file" to "settings").getString("cursor")

        settings.edit().putInt("launch_count", 4).commit()
        assertEquals("unchanged", get(routes, "entries", "file" to "settings", "cursor" to cursor).getString("status"))

        registry!!.start()
        settings.edit().putInt("launch_count", 5).commit()
        assertEquals("delta", get(routes, "entries", "file" to "settings", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `a discovered file that the tab opens while started is listened to`() {
        val routes = routes()
        registry!!.start()
        val cursor = get(routes, "entries", "file" to "sdk_cache").getString("cursor")
        prefs("sdk_cache").edit().putString("install_id", "i-10").commit()
        assertEquals("delta", get(routes, "entries", "file" to "sdk_cache", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `the tab's own edits move the cursor without the listener`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        var cursor = get(routes, "entries", "file" to "settings").getString("cursor")
        for (body in listOf(
            """{"file":"settings","key":"username","type":"string","value":"bob"}""",
            """{"file":"settings","key":"username"}""",
        )) {
            assertEquals(200, post(routes, if (body.contains("type")) "edit" else "delete", body).status)
            val delta = get(routes, "entries", "file" to "settings", "cursor" to cursor)
            assertEquals(body, "delta", delta.getString("status"))
            cursor = delta.getString("cursor")
        }
        assertEquals(200, post(routes, "clear", """{"file":"settings","confirm":true}""").status)
        assertEquals("delta", get(routes, "entries", "file" to "settings", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `an edit that changes nothing leaves the cursor`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        val cursor = get(routes, "entries", "file" to "settings").getString("cursor")
        post(routes, "edit", """{"file":"settings","key":"username","type":"string","value":"ann"}""")
        post(routes, "delete", """{"file":"settings","key":"nothing"}""")
        assertEquals("unchanged", get(routes, "entries", "file" to "settings", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `a cursor of one file is foreign to another`() {
        settings.edit().clear().putString("a", "1").commit()
        session.edit().clear().putString("a", "1").commit()
        val routes = routes(PrefsFile("settings", settings), PrefsFile("session", session))
        val cursor = get(routes, "entries", "file" to "settings").getString("cursor")
        assertEquals("reset", get(routes, "entries", "file" to "session", "cursor" to cursor).getString("status"))
    }

    @Test
    fun `the three policies`() {
        val routes =
            routes(
                PrefsFile("settings", settings, ValuePolicy.VISIBLE),
                PrefsFile("session", session, ValuePolicy.KEYS_ONLY),
                PrefsFile("sdk_cache", prefs("sdk_cache")),
            )
        val visible = get(routes, "entries", "file" to "settings")
        assertEquals("t0k3n", item(visible, "auth_token").getString("value"))
        assertFalse(item(visible, "auth_token").has("masked"))

        val keysOnly = get(routes, "entries", "file" to "session")
        for (key in keys(keysOnly)) {
            assertFalse(key, item(keysOnly, key).has("value"))
            assertTrue(key, item(keysOnly, key).getBoolean("masked"))
        }
        assertEquals("long", item(keysOnly, "expires_at").getString("type"))
        assertEquals(8, item(keysOnly, "expires_at").getInt("bytes"))

        val masked = get(routes, "entries", "file" to "sdk_cache")
        assertEquals("i-9", item(masked, "install_id").getString("value"))
        assertFalse(item(masked, "api_key").has("value"))
        assertTrue(item(masked, "api_key").getBoolean("masked"))
        assertEquals(3, item(masked, "api_key").getInt("bytes"))
    }

    @Test
    fun `a masked value can still be written, and the response masks it too`() {
        val routes = routes(PrefsFile("settings", settings))
        val edit = post(routes, "edit", """{"file":"settings","key":"auth_token","type":"string","value":"expired"}""")
        assertEquals(200, edit.status)
        val item = JSONObject(edit.text()).getJSONObject("item")
        assertTrue(item.getBoolean("masked"))
        assertFalse(item.has("value"))
        assertEquals("expired", settings.getString("auth_token", null))
    }

    @Test
    fun `a read-only file refuses edit, delete, and clear with an enveloped 403`() {
        val routes = routes(PrefsFile("session", session, ValuePolicy.VISIBLE, readOnly = true))
        for ((route, body) in listOf(
            "edit" to """{"file":"session","key":"session_id","type":"string","value":"x"}""",
            "delete" to """{"file":"session","key":"session_id"}""",
            "clear" to """{"file":"session","confirm":true}""",
        )) {
            val response = post(routes, route, body)
            assertEquals(route, 403, response.status)
            val error = JSONObject(response.text())
            assertEquals("forbidden", error.getString("error"))
            assertEquals("read_only", error.getString("code"))
            assertEquals("file", error.getString("field"))
        }
        assertEquals("s-1", session.getString("session_id", null))
        assertEquals(403, post(routes(), "edit", """{"file":"sdk_cache","key":"k","type":"string","value":"v"}""").status)
    }

    @Test
    fun `an encrypted file read plain shows keys only and is read-only, even when registered writable`() {
        val raw = prefs("secure")
        raw.edit().putString(PrefsSource.KEYSET_KEYS[0], "keyset").putString(PrefsSource.KEYSET_KEYS[1], "keyset").putString("AT3jx9", "AV9b").commit()
        val routes = routes(PrefsFile("secure", raw, ValuePolicy.VISIBLE))
        val file = file(routes, "secure")
        assertTrue(file.getBoolean("encrypted"))
        assertEquals("keys_only", file.getString("policy"))
        assertTrue(file.getBoolean("readOnly"))
        val entries = get(routes, "entries", "file" to "secure")
        assertTrue(item(entries, "AT3jx9").getBoolean("masked"))
        assertEquals(403, post(routes, "edit", """{"file":"secure","key":"AT3jx9","type":"string","value":"plain"}""").status)

        val discovered = routes()
        assertTrue(file(discovered, "secure").getBoolean("encrypted"))
        assertEquals("keys_only", file(discovered, "secure").getString("policy"))
    }

    @Test
    fun `a decrypting instance under another class name is still found, and stays writable`() {
        val secure = DecryptingPrefs(prefs("secure"))
        secure.edit().putString("refresh_token", "rt-1").putString("theme", "dark").commit()
        val routes = routes(PrefsFile("secure", secure, ValuePolicy.VISIBLE))
        val file = file(routes, "secure")
        assertTrue(file.getBoolean("encrypted"))
        assertEquals("visible", file.getString("policy"))
        assertFalse(file.getBoolean("readOnly"))
        assertEquals(2, file.getInt("keyCount"))

        val edit = post(routes, "edit", """{"file":"secure","key":"theme","type":"string","value":"light"}""")
        assertEquals(edit.text(), 200, edit.status)
        assertEquals("light", secure.getString("theme", null))
    }

    @Test
    fun `a clear through a decrypting instance moves the cursor, which its own listeners never hear`() {
        val secure = DecryptingPrefs(prefs("secure"))
        secure.edit().putString("refresh_token", "rt-1").commit()
        val routes = routes(PrefsFile("secure", secure, ValuePolicy.KEYS_ONLY))
        registry!!.start()
        val cursor = get(routes, "entries", "file" to "secure").getString("cursor")

        secure.edit().clear().commit()

        val delta = get(routes, "entries", "file" to "secure", "cursor" to cursor)
        assertEquals("delta", delta.getString("status"))
        assertEquals(0, delta.getJSONArray("items").length())
        registry!!.stop()
        secure.edit().putString("refresh_token", "rt-2").commit()
        val afterStop = delta.getString("cursor")
        assertEquals("stop removes both listeners", "unchanged", get(routes, "entries", "file" to "secure", "cursor" to afterStop).getString("status"))
    }

    @Test
    fun `edit writes every type with its setter, and returns what the file holds`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        val cases =
            listOf(
                Triple("string", "\"bob\"", "bob"),
                Triple("int", "\"7\"", 7),
                Triple("long", "9007199254740993", 9_007_199_254_740_993L),
                Triple("float", "0.5", 0.5f),
                Triple("boolean", "\"true\"", true),
                Triple("string_set", "[\"b\",\"a\"]", setOf("a", "b")),
            )
        for ((type, value, stored) in cases) {
            val response = post(routes, "edit", """{"file":"settings","key":"k_$type","type":"$type","value":$value}""")
            assertEquals(type, 200, response.status)
            val item = JSONObject(response.text()).getJSONObject("item")
            assertEquals(type, item.getString("type"))
            assertEquals(stored, settings.all["k_$type"])
        }
        val set = JSONObject(post(routes, "edit", """{"file":"settings","key":"k_string_set","type":"string_set","value":["b","a"]}""").text())
        assertEquals(JSONArray(listOf("a", "b")).toString(), set.getJSONObject("item").getJSONArray("value").toString())
    }

    @Test
    fun `edit can change the type of a key`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        assertEquals(200, post(routes, "edit", """{"file":"settings","key":"launch_count","type":"string","value":"three"}""").status)
        assertEquals("three", settings.all["launch_count"])
    }

    @Test
    fun `edit rejects a body without a key, a type, or a value of its type`() {
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        val cases =
            listOf(
                """{"file":"settings","type":"string","value":"x"}""" to "key",
                """{"file":"settings","key":5,"type":"string","value":"x"}""" to "key",
                """{"file":"settings","key":"k","value":"x"}""" to "type",
                """{"file":"settings","key":"k","type":"double","value":"x"}""" to "type",
                """{"file":"settings","key":"k","type":"unknown","value":"x"}""" to "type",
                """{"file":"settings","key":"k","type":"string"}""" to "value",
                """{"file":"settings","key":"k","type":"string","value":null}""" to "value",
                """{"file":"settings","key":"k","type":"int","value":"x"}""" to "value",
                """{"key":"k","type":"string","value":"x"}""" to "file",
                "[1]" to "body",
                "" to "body",
            )
        for ((body, field) in cases) {
            val response = post(routes, "edit", body)
            assertEquals(body, 400, response.status)
            assertEquals(body, field, JSONObject(response.text()).getString("field"))
        }
        assertNull(settings.all["k"])
    }

    @Test
    fun `a file name with a path, or one that names no file, is refused`() {
        val routes = routes(PrefsFile("settings", settings))
        for (name in listOf("../settings", "a/b", "a\\b", "")) {
            val response = call(routes, "GET", "entries", "file" to name)
            assertEquals(name, 400, response.status)
            assertEquals("file", JSONObject(response.text()).getString("field"))
        }
        assertEquals(400, call(routes, "GET", "entries").status)
        assertEquals(400, post(routes, "edit", """{"file":"../x","key":"k","type":"string","value":"v"}""").status)

        val missing = call(routes, "GET", "entries", "file" to "nothing")
        assertEquals(404, missing.status)
        assertEquals("file", JSONObject(missing.text()).getString("field"))
        assertFalse("a request creates no file", File(directory, "nothing.xml").exists())
    }

    @Test
    fun `delete says whether the key was there`() {
        val routes = routes(PrefsFile("settings", settings))
        assertTrue(JSONObject(post(routes, "delete", """{"file":"settings","key":"username"}""").text()).getBoolean("deleted"))
        assertFalse(settings.contains("username"))
        assertFalse(JSONObject(post(routes, "delete", """{"file":"settings","key":"username"}""").text()).getBoolean("deleted"))
        assertEquals("key", JSONObject(post(routes, "delete", """{"file":"settings"}""").text()).getString("field"))
    }

    @Test
    fun `clear needs confirm true, and says how many keys it removed`() {
        val routes = routes(PrefsFile("settings", settings))
        for (body in listOf("""{"file":"settings"}""", """{"file":"settings","confirm":"true"}""", """{"file":"settings","confirm":false}""")) {
            val response = post(routes, "clear", body)
            assertEquals(body, 400, response.status)
            assertEquals("confirm", JSONObject(response.text()).getString("field"))
        }
        assertEquals(3, settings.all.size)
        val cleared = post(routes, "clear", """{"file":"settings","confirm":true}""")
        assertEquals(3, JSONObject(cleared.text()).getInt("removed"))
        assertTrue(settings.all.isEmpty())
    }

    @Test
    fun `entries cuts a long value, and entry returns it whole`() {
        val long = "x".repeat(PrefValues.MAX_VALUE_CHARS + 10)
        settings.edit().putString("blob", long).commit()
        val routes = routes(PrefsFile("settings", settings, ValuePolicy.VISIBLE))
        val cut = item(get(routes, "entries", "file" to "settings"), "blob")
        assertEquals(PrefValues.MAX_VALUE_CHARS, cut.getString("value").length)
        assertTrue(cut.getBoolean("truncated"))
        assertEquals(long.length, cut.getInt("bytes"))

        val whole = get(routes, "entry", "file" to "settings", "key" to "blob").getJSONObject("item")
        assertEquals(long, whole.getString("value"))
        assertFalse(whole.has("truncated"))

        val missing = call(routes, "GET", "entry", "file" to "settings", "key" to "nothing")
        assertEquals(404, missing.status)
        assertEquals("key", JSONObject(missing.text()).getString("field"))
        assertEquals(400, call(routes, "GET", "entry", "file" to "settings").status)
    }

    @Test
    fun `a file whose instance can't read it is listed with the reason, and entries is a 500`() {
        val routes = routes(PrefsFile("broken", BrokenPrefs()))
        val file = file(routes, "broken")
        assertEquals(0, file.getInt("keyCount"))
        assertTrue(file.getString("error"), file.getString("error").contains("key is gone"))
        val entries = call(routes, "GET", "entries", "file" to "broken")
        assertEquals(500, entries.status)
        assertTrue(JSONObject(entries.text()).getString("message").contains("key is gone"))
    }

    @Test
    fun `a route takes one method, and an unknown route is not handled`() {
        val routes = routes(PrefsFile("settings", settings))
        assertEquals(405, call(routes, "POST", "files").status)
        assertEquals(405, call(routes, "GET", "edit").status)
        assertNull(routes.handle(DebugRequest("nothing", "GET")))
    }

    private fun prefs(name: String): SharedPreferences = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun routes(
        vararg files: PrefsFile,
        discover: Boolean = true,
        discoveredReadOnly: Boolean = true,
    ): PrefsRoutes {
        registry?.stop()
        val created = PrefsRegistry(files.toList(), if (discover) directory else null, discoveredReadOnly) { prefs(it) }
        registry = created
        return PrefsRoutes(created)
    }

    private fun call(routes: PrefsRoutes, method: String, path: String, vararg query: Pair<String, String>, body: String? = null): DebugResponse =
        routes.handle(DebugRequest(path, method, query.associate { it.first to listOf(it.second) }, body = body?.toByteArray()))!!

    private fun get(routes: PrefsRoutes, path: String, vararg query: Pair<String, String>): JSONObject {
        val response = call(routes, "GET", path, *query)
        assertEquals(response.text(), 200, response.status)
        return JSONObject(response.text())
    }

    private fun post(routes: PrefsRoutes, path: String, body: String): DebugResponse = call(routes, "POST", path, body = body)

    private fun file(routes: PrefsRoutes, name: String): JSONObject {
        val items = get(routes, "files").getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("name") == name }
    }

    private fun keys(envelope: JSONObject): List<String> {
        val items = envelope.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("key") }
    }

    private fun item(envelope: JSONObject, key: String): JSONObject {
        val items = envelope.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("key") == key }
    }

    private class BrokenPrefs : SharedPreferences {
        override fun getAll(): Map<String, *> = throw SecurityException("the key is gone")

        override fun getString(key: String?, defValue: String?): String? = defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = false

        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    }
}

internal fun DebugResponse.text(): String = body.toString(Charsets.UTF_8)
