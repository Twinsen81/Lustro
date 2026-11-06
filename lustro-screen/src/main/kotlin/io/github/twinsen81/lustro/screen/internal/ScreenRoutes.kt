package io.github.twinsen81.lustro.screen.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse

/** The routes of the Screen tab, under `/api/v1/screen/`. */
internal class ScreenRoutes(private val source: ScreenSource) {
    fun handle(request: DebugRequest): DebugResponse? {
        val expected = METHODS[request.path] ?: return null
        if (request.method.uppercase() != expected) {
            return DebugResponse.error("${request.path} takes $expected", status = 405)
        }
        return try {
            when (request.path) {
                "tree" -> tree(request)
                else -> null
            }
        } catch (e: RouteException) {
            DebugResponse.error(e.message.orEmpty(), status = e.status, code = e.code, field = e.field, hint = e.hint)
        } catch (e: ScreenSource.MainThreadBlocked) {
            DebugResponse.error(
                e.message.orEmpty(),
                status = 504,
                code = "main_thread_blocked",
                hint = "The app's main thread is busy or not responding; try again, or look for an ANR in the log",
            )
        } catch (_: InterruptedException) {
            // The runtime interrupts the thread when it gives up on the request.
            Thread.currentThread().interrupt()
            DebugResponse.error("The request timed out or the server stopped", status = 504)
        }
    }

    private fun tree(request: DebugRequest): DebugResponse {
        val merged = boolean(request, "merged", default = true)
        val settle = settle(request.queryParam("settle"))
        val tree = source.tree(request, merged, settle)
        return DebugResponse.json { ScreenJson.appendTree(this, tree) }
    }

    private fun boolean(request: DebugRequest, name: String, default: Boolean): Boolean =
        when (val value = request.queryParam(name)) {
            null, "" -> default
            "true" -> true
            "false" -> false
            else -> throw RouteException("$name must be true or false, was $value", field = name)
        }

    private fun settle(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        val value = text.toIntOrNull()
        if (value == null || value < 0 || value > ScreenSource.MAX_SETTLE_MS) {
            throw RouteException("settle must be a whole number of milliseconds from 0 to ${ScreenSource.MAX_SETTLE_MS}, was $text", field = "settle")
        }
        return value
    }

    /** A request that the route refuses, with the error envelope's fields. */
    class RouteException(
        message: String,
        val status: Int = 400,
        val code: String? = null,
        val field: String? = null,
        val hint: String? = null,
    ) : Exception(message)

    private companion object {
        val METHODS: Map<String, String> = mapOf("tree" to "GET")
    }
}
