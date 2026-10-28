package io.github.twinsen81.lustro.sqlite.internal

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a statement may hold its lock on a connection that the tab opened
 * itself, next to the app's own.
 *
 * Out of WAL mode, a reader holds a SHARED lock for as long as its statement
 * runs, and the app's writer waits for it. Android's SQLite gives up on a lock
 * after 2.5 s, and the app's write then fails with `SQLiteDatabaseLockedException`:
 * the request's timeout, 30 s by default, does not prevent that. So a statement
 * there stops after [limitMs], before that. The lock ends with each statement,
 * so the limit is for each one, not for the request. Through the app's own
 * handle, a read uses the app's connection pool, where a writer waits for the
 * connection without a time limit.
 */
internal class ReadLimit(private val limitMs: Long = LIMIT_MS) {
    /** Whether the statements on [connection] need the limit: the tab opened it, and the database is not in WAL mode. */
    fun applies(connection: DbConnection, signal: CancellationSignal): Boolean =
        connection.separate &&
            connection.query("PRAGMA journal_mode", emptyArray(), signal).use { !(it.moveToFirst() && it.getString(0).equals("wal", ignoreCase = true)) }

    /**
     * Runs one statement, [block], with a signal of its own, which [request] cancels, and the
     * limit after [limitMs]. A stop by the limit is a 504 with code `read_limit` and [field].
     */
    fun <T> statement(request: CancellationSignal, field: String?, block: (CancellationSignal) -> T): T {
        val statement = CancellationSignal()
        // Runs at once when the request is cancelled already. The statements of a request run one after another.
        request.setOnCancelListener { statement.cancel() }
        val limited = AtomicBoolean()
        val timer =
            TIMER.schedule({
                limited.set(true)
                statement.cancel()
            }, limitMs, TimeUnit.MILLISECONDS)
        try {
            return block(statement)
        } catch (e: OperationCanceledException) {
            if (!limited.get()) throw e
            throw RouteException(
                "A statement was stopped after $limitMs ms: the database is not in WAL mode, so a longer read would block the app's writes " +
                    "until they fail. Read less in one statement: a query with a LIMIT, or a WHERE that an index serves; GET rows with a " +
                    "smaller limit, or without a filter",
                status = 504,
                code = "read_limit",
                field = field,
            )
        } finally {
            timer.cancel(false)
            request.setOnCancelListener(null)
        }
    }

    companion object {
        /** How long a statement runs on a connection of the tab's own, out of WAL mode. */
        const val LIMIT_MS: Long = 2_000

        private val TIMER =
            ScheduledThreadPoolExecutor(1) { task -> Thread(task, "lustro-sqlite-limit").apply { isDaemon = true } }.apply {
                setKeepAliveTime(1, TimeUnit.SECONDS)
                allowCoreThreadTimeOut(true)
                removeOnCancelPolicy = true
            }
    }
}
