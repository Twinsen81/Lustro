package io.github.twinsen81.lustro.sqlite

import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.startup.InitializationProvider
import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.sqlite.internal.Fixtures
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.SQLiteMode

/** The SQLite routes through the real server, with its token auth. */
@RunWith(RobolectricTestRunner::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteDebugTabServerTest {
    private val http = OkHttpClient()
    private lateinit var db: SQLiteDatabase
    private lateinit var lustro: Lustro
    private lateinit var root: String
    private lateinit var base: String

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // The server reads its token from this file, and writes the port it bound.
        val prefs = app.getSharedPreferences("lustro_debug", Context.MODE_PRIVATE)
        prefs.edit().putString("lustro_token", TOKEN).commit()
        val databases = File(app.dataDir, "databases").apply { mkdirs() }
        db = SQLiteDatabase.openOrCreateDatabase(File(databases, "app.db"), null)
        Fixtures.create(db)
        Fixtures.file(databases, "library.db")

        lustro =
            Lustro.builder(app)
                .config(DebugConfig.builder().serverPort(0).build())
                .addTab(SqliteDebugTab.create(listOf(SqliteDb.framework("app", writable = true) { db }), discoverFrom = app))
                .build()
        // The server listens while the app is in the foreground: an activity puts it there.
        // Robolectric creates no content provider, so ProcessLifecycleOwner is attached here.
        Robolectric.setupContentProvider(InitializationProvider::class.java)
        Robolectric.buildActivity(Activity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(LustroStatus.ENABLED, lustro.start())

        val deadline = System.currentTimeMillis() + 5_000
        while (prefs.getInt("lustro_port", 0) == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        root = "http://127.0.0.1:${prefs.getInt("lustro_port", 0)}"
        base = "$root/api/v1/sqlite"
    }

    @After
    fun tearDown() {
        lustro.stop()
        db.close()
    }

    @Test
    fun `databases, tables, schema, and a page of rows`() {
        val databases = JSONObject(get("databases").body!!.string()).getJSONArray("items")
        assertEquals("app", databases.getJSONObject(0).getString("name"))
        assertEquals("library.db", databases.getJSONObject(1).getString("name"))
        assertEquals(2, databases.length())

        val tables = JSONObject(get("tables?db=app").body!!.string())
        assertTrue(tables.getBoolean("writable"))
        val schema = JSONObject(get("schema?db=app&table=notes").body!!.string())
        assertEquals(3, schema.getInt("rowCount"))

        val page = JSONObject(get("rows?db=app&table=notes&limit=2&sort=title&dir=desc").body!!.string())
        assertEquals(2, page.getJSONArray("items").length())
        val next = JSONObject(get("rows?db=app&table=notes&limit=2&sort=title&dir=desc&cursor=${page.getString("nextCursor")}").body!!.string())
        assertEquals("1", next.getJSONArray("items").getJSONObject(0).getString("rowid"))
        assertTrue(next.isNull("nextCursor"))

        val library = JSONObject(get("rows?db=library.db&table=folders").body!!.string())
        assertEquals(1, library.getJSONArray("items").length())
    }

    @Test
    fun `an error is the enveloped error, with its field`() {
        val response = get("rows?db=app&table=notes&sort=nope")
        assertEquals(400, response.code)
        val error = JSONObject(response.body!!.string())
        assertEquals("bad_request", error.getString("error"))
        assertEquals("sort", error.getString("field"))
        assertEquals(404, get("tables?db=nope").code)
    }

    @Test
    fun `a request without the token gets the enveloped 401`() {
        val response = http.newCall(Request.Builder().url("$base/databases").build()).execute()
        response.use {
            assertEquals(401, it.code)
            assertEquals("unauthorized", JSONObject(it.body!!.string()).getString("error"))
        }
    }

    @Test
    fun `the schema is served, _meta lists the tab, and the tab bar does not`() {
        val schema = get("_schema")
        assertEquals(200, schema.code)
        val paths = JSONObject(schema.body!!.string()).getJSONObject("paths")
        assertTrue(paths.has("/api/v1/sqlite/rows"))

        val meta = http.newCall(Request.Builder().url("$root/api/v1/_meta").header("Authorization", "Bearer $TOKEN").build()).execute()
        assertTrue(meta.body!!.string().contains("\"sqlite\""))
        val page = http.newCall(Request.Builder().url("$root/").build()).execute()
        assertFalse(page.body!!.string().contains("href=\"/tab/sqlite\""))
    }

    private fun get(path: String): Response =
        http.newCall(Request.Builder().url("$base/$path").header("Authorization", "Bearer $TOKEN").build()).execute()

    private companion object {
        const val TOKEN = "test-token-0123456789"
    }
}
