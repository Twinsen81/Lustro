package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.DebugResponse

/**
 * The longest throttle: ten minutes. A longer hold looks like a hung app, and it holds every
 * OkHttp request of the app. The declaration of `POST network/throttle` refuses a longer one.
 */
internal const val MAX_THROTTLE_MS = 600_000

/** `POST network/throttle`: sets the delay of [store] to [delayMs], which the declaration checked. */
internal fun setThrottle(store: NetworkTrafficStore, delayMs: Int): DebugResponse {
    store.setThrottleDelayMs(delayMs)
    return DebugResponse.json { append("{\"status\":\"ok\",\"delayMs\":$delayMs}") }
}
