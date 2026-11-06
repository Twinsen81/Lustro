package io.github.twinsen81.lustro.screen.internal

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.app
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.get
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.json
import io.github.twinsen81.lustro.screen.internal.ScreenFixtures.tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Nothing that a read meets reaches the app: no Compose class in an app without Compose, and no exception on its main thread. */
@RunWith(RobolectricTestRunner::class)
class ContainmentTest {
    @Test
    fun `the public factory and a read work in an app without Compose`() {
        ScreenFixtures.viewActivity()
        val loader = WithoutCompose(javaClass.classLoader!!)
        val factory = loader.loadClass("io.github.twinsen81.lustro.screen.ScreenDebugTab").getMethod("create", Context::class.java)
        val tab = factory.invoke(null, app) as DebugTab
        val tree = JSONObjectOf(String(tab.handle(DebugRequest("tree", "GET"))!!.body))
        assertFalse(tree.getBoolean("compose"))
        assertEquals("view", tree.getJSONArray("windows").getJSONObject(0).getJSONArray("nodes").getJSONObject(0).getString("kind"))
        assertTrue(loader.refused.any { it.startsWith("androidx.compose.") })
    }

    @Test
    fun `a View that throws in a read gives a 500, and the main thread goes on`() {
        val activity = ScreenFixtures.viewActivity()
        add(activity, Throwing(activity, failFrom = 0))
        val response = ScreenFixtures.getOffMain(tab(), "tree")
        assertEquals(500, response.status)
        val error = json(response)
        assertEquals("read_failed", error.getString("code"))
        assertTrue(error.getString("message"), error.getString("message").contains("IllegalStateException: a broken getter"))
        // The main looper runs on: an exception there would come out of this idle.
        ScreenFixtures.idle()
        assertEquals(500, get(tab(), "tree").status)
    }

    @Test
    fun `a View that throws in a settle frame gives a 500, and the main thread goes on`() {
        val activity = ScreenFixtures.viewActivity()
        // The first read passes; the read on the next frame throws.
        add(activity, Throwing(activity, failFrom = 1))
        val response = ScreenFixtures.getOffMain(tab(), "tree", "settle" to "500")
        assertEquals(500, response.status)
        assertEquals("read_failed", json(response).getString("code"))
        ScreenFixtures.idle()
    }

    @Test
    fun `a provider that throws an Error gives its message as contextError`() {
        ScreenFixtures.viewActivity()
        val tree = json(ScreenFixtures.getOffMain(tab(contextProvider = { TODO("no route yet") }), "tree"))
        assertTrue(tree.isNull("context"))
        assertEquals("The ScreenContextProvider threw kotlin.NotImplementedError: An operation is not implemented: no route yet", tree.getString("contextError"))
    }

    private fun add(activity: Activity, view: View) {
        (activity.findViewById<View>(android.R.id.content) as android.view.ViewGroup).getChildAt(0).let { (it as LinearLayout).addView(view) }
        ScreenFixtures.idle()
    }

    /** A View whose content description getter throws from its [failFrom]th call on. */
    private class Throwing(context: Context, private val failFrom: Int) : View(context) {
        private var calls = 0

        override fun getContentDescription(): CharSequence? {
            if (calls++ >= failFrom) error("a broken getter")
            return null
        }
    }

    private fun JSONObjectOf(text: String) = org.json.JSONObject(text)

    /**
     * Loads the tab's own classes itself, and refuses every Compose class, as the class
     * loader of an app without Compose does. The rest comes from the test's loader.
     */
    private class WithoutCompose(private val parent: ClassLoader) : ClassLoader(parent) {
        val refused = mutableSetOf<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("androidx.compose.")) {
                refused += name
                throw ClassNotFoundException(name)
            }
            if (!name.startsWith("io.github.twinsen81.lustro.screen.")) return super.loadClass(name, resolve)
            synchronized(this) {
                findLoadedClass(name)?.let { return it }
                val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.use { it.readBytes() }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }
}
