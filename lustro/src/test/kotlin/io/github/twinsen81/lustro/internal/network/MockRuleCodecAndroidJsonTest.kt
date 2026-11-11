package io.github.twinsen81.lustro.internal.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [MockRuleCodec] against the JSON parser it actually runs on. Android's
 * `org.json` gives the text `null` for a `null` value in `optString`. The rest
 * of the codec's tests run on the JVM's `org.json`, which gives an empty
 * string, and so can't tell a `null` id that gets a new id from one that is
 * kept as the id `null`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MockRuleCodecAndroidJsonTest {
    private fun parse(json: String, generateMissingId: Boolean): MockRuleParseResult =
        MockRuleCodec.parse(JSONObject(json), generateMissingId = generateMissingId)

    @Test
    fun `a null id gets a new id, as a missing one does`() {
        val ids =
            listOf("""{"id":null,"urlPattern":"/a"}""", """{"id":null,"urlPattern":"/b"}""").map { json ->
                val result = parse(json, generateMissingId = true)
                assertTrue("expected $json to be accepted, got $result", result is MockRuleParseResult.Valid)
                (result as MockRuleParseResult.Valid).rule.id
            }
        assertTrue("no id is the text null: $ids", "null" !in ids)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `a stored rule with a null id is rejected, as one with no id is`() {
        val result = parse("""{"id":null,"urlPattern":"/a"}""", generateMissingId = false)
        assertTrue("expected a rejection, got $result", result is MockRuleParseResult.Invalid)
        assertEquals("id", (result as MockRuleParseResult.Invalid).field)
    }
}
