package io.github.twinsen81.lustro.internal

import androidx.test.core.app.ApplicationProvider
import io.github.twinsen81.lustro.network.NetworkDebugTab
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CSP regression guard. The chrome page and tab `_view`
 * responses carry `script-src 'self'` with NO `'unsafe-inline'`, so any inline
 * `on*=` event-handler attribute in served HTML/JS would be BLOCKED by the
 * browser and silently break the UI.
 *
 * This test reads the served network tab `_view` HTML ([NetworkDebugTab.renderContent])
 * AND the `network.js` / `shared.js` asset contents and asserts none contains an
 * inline DOM event-handler attribute (e.g. `onclick=`, `oninput=`, `onchange=`).
 * It locks in the move to `addEventListener` / event delegation: if anyone
 * re-introduces an inline handler, this fails. (A DOM *property* assignment like
 * `el.onclick = fn` in JS is allowed — it is not an inline HTML attribute and is
 * not blocked by the CSP. The regex only matches the ` on<event>=` attribute
 * position, so it never false-positives on `el.onclick =` or words like
 * `version=`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineHandlerCspTest {
    // DOM event-handler attributes that the CSP (script-src 'self', no
    // 'unsafe-inline') would block. Anchored on a preceding whitespace/quote so a
    // JS property access (`.onclick`) or a substring (`version=`) cannot match.
    private val inlineHandlerRegex =
        Regex(
            "[\\s\"']on(" +
                "click|input|change|keydown|keyup|submit|load|" +
                "mouseover|mouseout|focus|blur|mousedown|mouseup|wheel|" +
                "dblclick|contextmenu|scroll|select|toggle" +
                ")\\s*=",
            RegexOption.IGNORE_CASE,
        )

    private fun assetLoader(): DebugAssetLoader =
        DebugAssetLoader(ApplicationProvider.getApplicationContext())

    private fun assertNoInlineHandlers(label: String, content: String) {
        val match = inlineHandlerRegex.find(content)
        assertTrue(
            "$label must contain no inline event-handler attribute (CSP forbids " +
                "inline JS), but found '${match?.value?.trim()}' near offset ${match?.range?.first}",
            match == null,
        )
    }

    @Test
    fun `served network tab view HTML has no inline event handlers`() {
        val html = NetworkDebugTab.create().renderContent()
        assertTrue("renderContent() should emit markup", html.isNotBlank())
        assertNoInlineHandlers("NetworkDebugTab.renderContent()", html)
    }

    @Test
    fun `network js asset has no inline event handlers`() {
        val js = assetLoader().load("network.js")
        assertNotNull("network.js asset must be present", js)
        assertNoInlineHandlers("network.js", js!!)
    }

    @Test
    fun `shared js asset has no inline event handlers`() {
        val js = assetLoader().load("shared.js")
        assertNotNull("shared.js asset must be present", js)
        assertNoInlineHandlers("shared.js", js!!)
    }

    @Test
    fun `shared js retries token auth on hash-only navigation`() {
        val js = assetLoader().load("shared.js")
        assertNotNull("shared.js asset must be present", js)
        assertTrue(js!!.contains("window.addEventListener('hashchange'"))
        assertTrue(js.contains("authenticateWithToken(token, true)"))
        assertTrue(js.contains("authenticateWithToken(token, needsContent)"))
        assertTrue(js.contains("document.querySelector('.lustro-auth-needed')"))
    }

    @Test
    fun `network forms expose stable labels and field names`() {
        val html = NetworkDebugTab.create().renderContent()
        val js = assetLoader().load("network.js")
        assertNotNull("network.js asset must be present", js)

        assertTrue(html.contains("for=\"search-input\""))
        assertTrue(html.contains("name=\"search\""))
        assertTrue(html.contains("name=\"throttleDelayMs\""))
        assertTrue(html.contains("aria-label=\"Global throttle\""))

        val networkJs = js!!
        listOf(
            "for=\"rf-name\"",
            "name=\"name\"",
            "for=\"rf-pattern\"",
            "name=\"urlPattern\"",
            "for=\"rf-method\"",
            "name=\"method\"",
            "for=\"rf-status\"",
            "name=\"statusCode\"",
            "for=\"rf-body\"",
            "name=\"responseBody\"",
            "for=\"sf-method\"",
            "for=\"sf-url\"",
            "name=\"url\"",
            "for=\"sf-body\"",
            "name=\"body\"",
            "id=\"sf-headers-label\"",
            "role=\"group\" aria-labelledby=\"sf-headers-label\"",
            "id=\"' + keyId + '\" name=\"headerKey\"",
            "id=\"' + valueId + '\" name=\"headerValue\"",
            "aria-label=\"Header name\"",
            "aria-label=\"Header value\"",
        ).forEach { expected ->
            assertTrue("network.js should contain $expected", networkJs.contains(expected))
        }
    }

    @Test
    fun `the guard regex actually detects an inline handler`() {
        // Sanity: a known-bad fragment must trip the guard, so a green suite means
        // the assertion has teeth rather than a regex that never matches.
        assertTrue(
            "regex must flag a real inline onclick attribute",
            inlineHandlerRegex.containsMatchIn("<button onclick=\"doThing()\">x</button>"),
        )
        assertTrue(
            "regex must flag an oninput attribute",
            inlineHandlerRegex.containsMatchIn("<input oninput=\"f(this.value)\">"),
        )
        // And must NOT flag a JS property assignment or an unrelated attribute.
        assertTrue(
            "regex must not flag a JS .onclick property assignment",
            !inlineHandlerRegex.containsMatchIn("el.onclick = function() {};"),
        )
        assertTrue(
            "regex must not flag a 'version=' attribute",
            !inlineHandlerRegex.containsMatchIn("<meta data-version=\"1\">"),
        )
    }
}
