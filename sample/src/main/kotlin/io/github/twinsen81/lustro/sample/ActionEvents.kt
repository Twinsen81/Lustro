package io.github.twinsen81.lustro.sample

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class Outcome {
    RUNNING,
    OK,

    /** The server answered with a 4xx or 5xx status. */
    HTTP_ERROR,

    /** No response: the request, the socket, or the action failed. */
    FAILED,
    INFO,
}

/**
 * One state of one action run. A run starts with [Outcome.RUNNING] or ends at
 * once, and each later state of it replaces the earlier one in [EventLog.recent].
 */
internal data class ActionEvent(
    val run: Long,
    val label: String?,
    val outcome: Outcome,
    val text: String,
    val detail: String?,
    val atMillis: Long,
)

/** The results the result bar and the action chips show. Thread-safe. */
internal class EventLog(private val clock: () -> Long = System::currentTimeMillis) {
    private val runs = AtomicLong()
    private val recentEvents = MutableStateFlow<List<ActionEvent>>(emptyList())
    private val latestEvents = MutableStateFlow<Map<String, ActionEvent>>(emptyMap())

    /** The newest first, at most [HISTORY_SIZE]. */
    val recent: StateFlow<List<ActionEvent>> = recentEvents.asStateFlow()

    /** The last event of each action label, which its chip shows. */
    val latestByLabel: StateFlow<Map<String, ActionEvent>> = latestEvents.asStateFlow()

    /** Starts a run. [label] is null for an event that no action started, such as a WebSocket message. */
    fun start(label: String?): Feedback = Feedback(this, runs.incrementAndGet(), label, moveToTop = true)

    internal fun post(feedback: Feedback, outcome: Outcome, text: String, detail: String?) {
        val event = ActionEvent(feedback.run, feedback.label, outcome, text, detail, clock())
        recentEvents.update { events ->
            val index = events.indexOfFirst { it.run == event.run }
            when {
                index < 0 || feedback.moveToTop -> (listOf(event) + events.filterNot { it.run == event.run }).take(HISTORY_SIZE)
                else -> events.toMutableList().also { it[index] = event }
            }
        }
        val label = event.label ?: return
        latestEvents.update { it + (label to event) }
    }

    companion object {
        const val HISTORY_SIZE: Int = 5
    }
}

/** Reports the states of one run. */
internal class Feedback(
    private val log: EventLog,
    val run: Long,
    val label: String?,
    val moveToTop: Boolean,
) {
    fun running(text: String, detail: String? = null) = log.post(this, Outcome.RUNNING, text, detail)

    fun ok(text: String, detail: String? = null) = log.post(this, Outcome.OK, text, detail)

    fun failed(text: String, detail: String? = null) = log.post(this, Outcome.FAILED, text, detail)

    fun info(text: String, detail: String? = null) = log.post(this, Outcome.INFO, text, detail)

    fun response(code: Int, text: String) = log.post(this, if (code >= HTTP_BAD_REQUEST) Outcome.HTTP_ERROR else Outcome.OK, text, null)

    /**
     * Updates the same history row without moving it to the top, and without
     * the chip of [label]: for a run that posts every few seconds.
     */
    fun quiet(): Feedback = Feedback(log, run, label = null, moveToTop = false)

    private companion object {
        const val HTTP_BAD_REQUEST = 400
    }
}
