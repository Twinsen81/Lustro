package io.github.twinsen81.lustro.screen.internal

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugRoutes
import io.github.twinsen81.lustro.DebugRoutes.Companion.ref
import io.github.twinsen81.lustro.ExperimentalDebugRoutes

/**
 * The routes of the Screen tab, under `/api/v1/screen/`. [declare] declares them, and the
 * tab's OpenAPI document comes from the declarations, with the text of [ScreenDocument].
 */
@OptIn(ExperimentalDebugRoutes::class)
internal class ScreenRoutes(private val source: ScreenSource) {
    fun declare(routes: DebugRoutes) {
        routes.title = ScreenDocument.TITLE
        routes.version = "1.0"
        routes.description = ScreenDocument.DESCRIPTION
        for ((name, schema) in SCREEN_SCHEMAS) routes.component(name, schema)

        routes.get("tree") {
            operationId = "getScreenTree"
            summary = "The app's windows, and the nodes of each."
            description = ScreenDocument.TREE_DESCRIPTION
            val merged = query.boolean("merged") { description = ScreenDocument.MERGED }.withDefault(true)
            val settle =
                query.int("settle") {
                    min = 0
                    max = ScreenSource.MAX_SETTLE_MS.toLong()
                    description = ScreenDocument.SETTLE
                }.withDefault(0)
            response(ScreenDocument.TREE_RESPONSE, ref("ScreenTree"))
            errors(500, 504)
            handle { call -> guarded { tree(call.request, call[merged], call[settle]) } }
        }
    }

    private fun tree(request: DebugRequest, merged: Boolean, settle: Int): DebugResponse {
        val tree = source.tree(request, merged, settle)
        return DebugResponse.json { ScreenJson.appendTree(this, tree) }
    }

    private inline fun guarded(answer: () -> DebugResponse): DebugResponse =
        try {
            answer()
        } catch (e: ScreenSource.ReadFailed) {
            DebugResponse.error(e.message.orEmpty(), status = 500, code = "read_failed")
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
