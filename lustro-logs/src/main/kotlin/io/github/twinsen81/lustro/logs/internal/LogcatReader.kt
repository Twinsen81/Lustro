package io.github.twinsen81.lustro.logs.internal

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.util.Locale

/**
 * Runs `logcat -v threadtime -v UTC --pid=<pid>` on a thread of its own, and
 * adds each line it parses to [buffer].
 *
 * The first run reads from [processStartMs], so the buffer also gets the lines
 * the app wrote before the tab started. A later run, after a failure or after
 * [stop] and [start], goes on from the newest line read, and skips the lines at
 * that time that it already read. Each run passes its start to logcat with
 * `-T`, and also skips the older lines itself: logcat on API 24 accepts `-T`
 * with a time but prints its whole buffer. When logcat ends, the reader starts
 * it again after [restartDelayMs]. After [MAX_RESTARTS] restarts in a row with
 * no line, it gives up and its [status] is [Status.FAILED]; the buffer keeps
 * what it has.
 *
 * [start] and [stop] are safe to call from any thread.
 */
internal class LogcatReader(
    private val buffer: LogBuffer,
    private val pid: Int = android.os.Process.myPid(),
    private val processStartMs: () -> Long = ::processStartWallClock,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val restartDelayMs: Long = RESTART_DELAY_MS,
    private val parser: ThreadtimeParser = ThreadtimeParser(),
) {
    enum class Status(val wireName: String) {
        STOPPED("stopped"),
        RUNNING("running"),
        RESTARTING("restarting"),
        FAILED("failed"),
    }

    private val lock = Any()

    // Guarded by lock.
    private var session: Session? = null
    private var thread: Thread? = null

    @Volatile
    var status: Status = Status.STOPPED
        private set

    /** Why logcat ended or couldn't start, when the last run read no line; else `null`. */
    @Volatile
    var error: String? = null
        private set

    // Where the next run starts: the time of the newest line read, and how many
    // lines at that time were read. Written only by the reader thread.
    @Volatile
    private var resumeAt = -1L

    @Volatile
    private var linesAtResume = 0

    /** The time of the newest line read, also while the buffer is paused, or -1 before the first. */
    val newestLineAt: Long get() = resumeAt

    // False once logcat rejected the time of -T: the runs then read without it.
    @Volatile
    private var passSince = true

    fun start() {
        synchronized(lock) {
            if (session != null) return
            val next = Session()
            session = next
            status = Status.RUNNING
            error = null
            thread = Thread(next, THREAD_NAME).apply {
                isDaemon = true
                start()
            }
        }
    }

    /**
     * Ends logcat and waits up to [JOIN_TIMEOUT_MS] for the reader thread. The
     * thread ends soon after logcat does, because its read then sees the end of
     * the stream.
     */
    fun stop() {
        val ending: Thread?
        synchronized(lock) {
            val current = session ?: return
            session = null
            current.end()
            ending = thread
            thread = null
            status = Status.STOPPED
        }
        ending?.interrupt()
        try {
            ending?.join(JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun arguments(since: Long): List<String> {
        // UTC, so that a change of the device's time zone can't shift the times it parses.
        val arguments = listOf("logcat", "-v", "threadtime", "-v", "UTC", "--pid=$pid")
        if (!passSince) return arguments
        // Seconds since the epoch: unlike MM-dd, it has no year and no time zone to get wrong.
        return arguments + listOf("-T", String.format(Locale.US, "%d.%03d", since / MILLIS_PER_SECOND, since % MILLIS_PER_SECOND))
    }

    private inner class Session : Runnable {
        @Volatile
        private var active = true

        @Volatile
        private var process: Process? = null

        fun end() {
            active = false
            process?.destroy()
        }

        // Nothing may leave this thread: on Android, an uncaught throwable, an Error
        // such as OutOfMemoryError included, kills the host app.
        override fun run() {
            try {
                readUntilEnded()
            } catch (t: Throwable) {
                report(Status.FAILED, "the reader stopped: $t")
                Log.w(TAG, "The Logs tab's logcat reader stopped", t)
            }
        }

        private fun readUntilEnded() {
            var failures = 0
            while (active) {
                val readAny = readOnce()
                if (!active) return
                failures = if (readAny) 0 else failures + 1
                if (failures > MAX_RESTARTS) {
                    report(Status.FAILED)
                    Log.w(TAG, "logcat ended ${failures}x in a row without a line; the Logs tab stops reading: $error")
                    return
                }
                report(Status.RESTARTING)
                try {
                    Thread.sleep(restartDelayMs)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }

        // A session that stop() ended must not overwrite what a later session reports.
        private fun report(newStatus: Status, newError: String? = error) {
            synchronized(lock) {
                if (session !== this) return
                status = newStatus
                error = newError
            }
        }

        // Returns whether the run parsed at least one line.
        private fun readOnce(): Boolean {
            // A resumed run starts at the time of the newest line read, and skips the
            // lines at that time that the last run read.
            val resumed = resumeAt >= 0
            val since = if (resumed) resumeAt else processStartMs()
            var skip = if (resumed) linesAtResume else 0
            var atStart = true
            val started =
                try {
                    launch(arguments(since))
                } catch (e: IOException) {
                    report(status, "logcat could not start: ${e.message}")
                    return false
                } catch (e: SecurityException) {
                    report(status, "logcat could not start: ${e.message}")
                    return false
                }
            process = started
            if (!active) {
                started.destroy()
                return false
            }
            report(Status.RUNNING)
            var readAny = false
            var lastOther: String? = null
            try {
                started.inputStream.use { input ->
                    val lines = LogcatLines(input)
                    while (active) {
                        if (!lines.next()) break
                        if (!active) break
                        val line = parser.parse(lines.line, lines.length)
                        if (line == null) {
                            val text = lines.text()
                            if (!text.startsWith(BANNER_PREFIX) && text.isNotBlank()) lastOther = text
                            continue
                        }
                        if (atStart) {
                            if (line.at < since) continue
                            if (line.at == since && skip > 0) {
                                skip--
                                continue
                            }
                            atStart = false
                        }
                        if (line.at == resumeAt) {
                            linesAtResume++
                        } else {
                            resumeAt = line.at
                            linesAtResume = 1
                        }
                        if (!readAny) {
                            readAny = true
                            report(Status.RUNNING, newError = null)
                        }
                        buffer.append(line)
                    }
                }
            } catch (e: IOException) {
                // destroy() closes the stream under the read: that is how stop() ends a run.
                if (active) lastOther = "logcat output could not be read: ${e.message}"
            } finally {
                started.destroy()
                process = null
            }
            if (active && !readAny) {
                report(status, lastOther?.let { "logcat ended: $it" } ?: "logcat ended without a line")
                // As "-T "1792419740.010" not in time format" from a logcat that has no such format.
                if (passSince && lastOther?.contains(TIME_FORMAT_ERROR) == true) passSince = false
            }
            return readAny
        }
    }

    companion object {
        const val MAX_RESTARTS: Int = 3
        const val RESTART_DELAY_MS: Long = 1_000
        const val JOIN_TIMEOUT_MS: Long = 1_000

        private const val TAG = "LustroLogs"
        private const val THREAD_NAME = "lustro-logcat"
        private const val BANNER_PREFIX = "--------- "
        private const val TIME_FORMAT_ERROR = "time format"
        private const val MILLIS_PER_SECOND = 1000L

        // The process start has no line of this process before it. A second earlier
        // allows for the error of mapping the elapsed-time clock to wall time.
        private const val START_MARGIN_MS = 1_000L

        /** When this process started, in epoch milliseconds. */
        fun processStartWallClock(): Long {
            val sinceStart = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()
            return System.currentTimeMillis() - sinceStart - START_MARGIN_MS
        }
    }
}
