package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.DefaultRedactor
import io.github.twinsen81.lustro.network.Redactor
import java.util.concurrent.Executor

/** Stands in for the capture thread: holds tasks until the test runs them. */
internal class ManualExecutor : Executor {
    private val queued = ArrayDeque<Runnable>()

    val pending: Int
        get() = queued.size

    override fun execute(command: Runnable) {
        queued.addLast(command)
    }

    fun runNext() {
        queued.removeFirst().run()
    }

    fun runAll() {
        while (queued.isNotEmpty()) runNext()
    }
}

/** Runs capture work on the calling thread, so a capture is stored before the call returns. */
internal fun inlineCaptureWorker(): CaptureWorker = CaptureWorker(executor = { it.run() })

/** [DefaultRedactor], counting the bodies it redacts. */
internal class CountingRedactor : Redactor by DefaultRedactor {
    var bodies: Int = 0
        private set

    override fun redactBody(body: String, contentType: MediaType?): String {
        bodies++
        return DefaultRedactor.redactBody(body, contentType)
    }
}
