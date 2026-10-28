package io.github.twinsen81.lustro.work.internal

import androidx.work.analytics.WorkMetricsInfo
import androidx.work.analytics.WorkMetricsInfoRepository
import androidx.work.analytics.WorkMetricsQuery
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * The history that work-analytics keeps on disk, read through the app's
 * [repository]. Only the app that passes a repository loads this class, so an app
 * without work-analytics never needs its classes.
 */
internal class WorkMetrics(private val repository: WorkMetricsInfoRepository) {
    /** The periods that match [ids], [tags], [states], and the enqueue times [from] to [to], oldest first. */
    fun query(ids: List<UUID>, tags: List<String>, states: List<String>, from: Long?, to: Long?): List<WorkMetricsInfo> {
        val builder = WorkMetricsQuery.Builder()
        if (ids.isNotEmpty()) builder.addWorkIds(ids)
        if (tags.isNotEmpty()) builder.addTags(tags)
        if (states.isNotEmpty()) builder.addStates(states.map { WorkMetricsInfo.State.valueOf(it) })
        from?.let { builder.setBeginTimeMillis(it) }
        to?.let { builder.setEndTimeMillis(it) }
        val query = builder.build()
        return awaitBlocking { repository.getWorkMetricsInfos(query) }
    }

    /** The periods of the work with [id], oldest first. */
    fun of(id: UUID): List<WorkMetricsInfo> = awaitBlocking { repository.getWorkMetricsInfoById(id) }.sortedBy { it.enqueueTimeMillis }

    companion object {
        /** The names of `WorkMetricsInfo.State`, which the `state` parameter of `GET metrics` takes. */
        fun stateNames(): List<String> = WorkMetricsInfo.State.values().map { it.name }

        /** Appends one period of a work's history. A time that work-analytics has not set yet is left out. */
        fun appendPeriod(out: StringBuilder, info: WorkMetricsInfo) {
            out.append("{\"id\":\"").append(info.workSpecId.toString()).append('"')
            out.append(",\"generation\":").append(info.generation)
            out.append(",\"workerClass\":")
            WorkJson.appendNullable(out, info.workerClassName)
            out.append(",\"tags\":")
            WorkJson.appendStrings(out, info.tags.sorted())
            out.append(",\"state\":\"").append(info.state.name).append('"')
            appendTime(out, "enqueueTimeMillis", info.enqueueTimeMillis)
            appendTime(out, "unblockTimeMillis", info.unblockTimeMillis)
            appendTime(out, "firstStartTimeMillis", info.firstStartTimeMillis)
            appendTime(out, "finishTimeMillis", info.finishTimeMillis)
            appendTime(out, "workerDurationMillis", info.workerDurationMillis)
            appendTime(out, "totalRuntimeMillis", info.totalRuntimeMillis)
            out.append(",\"runAttempts\":").append(info.runAttemptCount)
            out.append(",\"explicitRetries\":").append(info.explicitRetryCount)
            out.append(",\"stopReasons\":[")
            info.stopReasonCounts.entries.sortedBy { it.key }.forEachIndexed { index, (code, count) ->
                if (index > 0) out.append(',')
                out.append("{\"code\":").append(code).append(",\"name\":\"").append(WorkJson.stopReasonName(code))
                out.append("\",\"count\":").append(count).append('}')
            }
            out.append("]}")
        }

        // work-analytics writes -1 for a time it has not seen yet.
        private fun appendTime(out: StringBuilder, name: String, millis: Long) {
            if (millis >= 0) out.append(",\"").append(name).append("\":").append(millis)
        }

        /**
         * Runs the suspending [block] and waits for its result on the calling thread,
         * with the standard library's coroutine support only. The repository's calls
         * switch to its own threads. The wait ends when the runtime interrupts the
         * thread, at the request's timeout; the call then ends on its own.
         */
        private fun <T> awaitBlocking(block: suspend () -> T): T {
            val done = CountDownLatch(1)
            val outcome = AtomicReference<Result<T>>()
            block.startCoroutine(
                Continuation(EmptyCoroutineContext) {
                    outcome.set(it)
                    done.countDown()
                },
            )
            done.await()
            return outcome.get().getOrThrow()
        }
    }
}
