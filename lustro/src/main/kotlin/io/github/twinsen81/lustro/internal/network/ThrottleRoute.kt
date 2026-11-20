package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugResponse
import org.json.JSONException
import org.json.JSONObject

// Ten minutes. A longer hold looks like a hung app, and it holds every OkHttp request of the app.
private const val MAX_THROTTLE_MS = 600_000

/**
 * `POST network/throttle` with `{"delayMs": N}`: sets the delay of [store]. A missing `delayMs`,
 * or one that is not a whole number from 0 to [MAX_THROTTLE_MS], is a `400` with `field: delayMs`,
 * and the delay stays as it was.
 */
internal fun setThrottle(store: NetworkTrafficStore, body: String?): DebugResponse {
    val json =
        try {
            JSONObject(body ?: "{}")
        } catch (e: JSONException) {
            return DebugResponse.error("Invalid JSON: ${e.message}")
        }
    val given = json.opt("delayMs") ?: return DebugResponse.error("delayMs is required", field = "delayMs")
    val delayMs =
        throttleDelay(given) ?: return DebugResponse.error(
            "delayMs must be a whole number of milliseconds from 0 to $MAX_THROTTLE_MS, was ${if (given is String) JSONObject.quote(given) else given}",
            field = "delayMs",
        )
    store.setThrottleDelayMs(delayMs)
    return DebugResponse.json { append("{\"status\":\"ok\",\"delayMs\":$delayMs}") }
}

// Not optInt: it reads "x" and a missing value as 0, which turns the throttle off without an
// error. org.json gives 2000 as an Int and 2000.0 as a Double; both are whole.
private fun throttleDelay(value: Any): Int? {
    val number = (value as? Number)?.toDouble() ?: return null
    return number.toInt().takeIf { number % 1.0 == 0.0 && number in 0.0..MAX_THROTTLE_MS.toDouble() }
}
