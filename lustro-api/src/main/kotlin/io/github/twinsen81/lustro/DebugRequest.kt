package io.github.twinsen81.lustro

/**
 * An incoming debug API request handed to [DebugTab.handle].
 *
 * Constructed by the Lustro runtime, but the constructor is public so consumers
 * can build instances directly in unit tests; call [cancel] there to simulate
 * the runtime giving up on a request. The [path] is everything after
 * `/api/v1/<tab-id>/`.
 */
public class DebugRequest @JvmOverloads constructor(
    /** The request path after `/api/v1/<tab-id>/`. */
    public val path: String,
    /** The HTTP method, e.g. `GET` or `POST`. */
    public val method: String,
    /** Query parameters, each name mapped to its ordered list of values. */
    public val queryParams: Map<String, List<String>> = emptyMap(),
    /**
     * The request headers. The runtime leaves out `Authorization` and `Cookie`,
     * which carry the debug session's credentials.
     */
    public val headers: Headers = Headers.EMPTY,
    /** The raw request body bytes, or `null` if there is no body. */
    public val body: ByteArray? = null,
    /** The parsed `Content-Type` of the body, or `null` if absent/unparseable. */
    public val contentType: MediaType? = null,
) {
    private val cancelLock = Any()

    // Null once cancelled. Guarded by cancelLock.
    private var cancelActions: MutableList<Runnable>? = ArrayList(1)

    @Volatile
    private var cancelled = false

    /**
     * Whether the runtime has stopped waiting for this request: it outlived the
     * per-request timeout, so the client already got a `504`, or the debug server
     * is shutting down. Whatever [DebugTab.handle] returns after that is
     * discarded, so long-running work should check this and return early.
     */
    public val isCancelled: Boolean get() = cancelled

    /**
     * Runs [action] when this request is cancelled, or right away, on the
     * calling thread, if it already is.
     *
     * Use it to abort blocking calls that ignore thread interrupts, such as
     * cancelling the `android.os.CancellationSignal` passed to
     * `SQLiteDatabase.rawQuery` or an OkHttp `Call`. The runtime runs [action]
     * on its own thread, and a cancellation that races the handler's return can
     * run it after [DebugTab.handle] has returned, so keep it short,
     * non-blocking, and harmless to run late.
     */
    public fun onCancel(action: Runnable) {
        synchronized(cancelLock) {
            val actions = cancelActions
            if (actions != null) {
                actions.add(action)
                return
            }
        }
        action.run()
    }

    /**
     * Cancels this request: [isCancelled] turns `true` and every action
     * registered with [onCancel] runs once, in registration order, on the
     * calling thread. Later calls do nothing. If an action throws, the rest
     * still run and the first failure is rethrown afterwards.
     */
    public fun cancel() {
        val actions =
            synchronized(cancelLock) {
                cancelled = true
                cancelActions.also { cancelActions = null }
            } ?: return
        var failure: Throwable? = null
        for (action in actions) {
            try {
                action.run()
            } catch (t: Throwable) {
                val first = failure
                if (first == null) failure = t else first.addSuppressed(t)
            }
        }
        failure?.let { throw it }
    }

    /**
     * Returns the first value of the query parameter named [name], or `null`
     * if the parameter is absent or has no values.
     */
    public fun queryParam(name: String): String? = queryParams[name]?.firstOrNull()

    /**
     * Returns the request [body] decoded as a UTF-8 string, or `null` if there
     * is no body.
     */
    public fun bodyAsString(): String? = body?.toString(Charsets.UTF_8)
}
