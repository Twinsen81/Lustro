package io.github.twinsen81.lustro.logs.internal

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A logcat process for tests: [emit] writes lines, and [end] or [destroy] ends its output. */
internal class FakeLogcat : Process() {
    private val bytes = LinkedBlockingQueue<Int>()

    @Volatile
    var destroyed: Boolean = false
        private set

    fun emit(vararg lines: String): FakeLogcat {
        for (line in lines) "$line\n".toByteArray().forEach { bytes.offer(it.toInt() and 0xff) }
        return this
    }

    fun end(): FakeLogcat {
        bytes.offer(END)
        return this
    }

    override fun destroy() {
        destroyed = true
        end()
    }

    override fun getInputStream(): InputStream =
        object : InputStream() {
            // A read from a pipe ignores interrupts, as this one does.
            override fun read(): Int {
                var next: Int? = null
                while (next == null) {
                    next =
                        try {
                            bytes.take()
                        } catch (_: InterruptedException) {
                            null
                        }
                }
                if (next == END) bytes.offer(END)
                return next
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val first = read()
                if (first == END) return -1
                buffer[offset] = first.toByte()
                var count = 1
                while (count < length) {
                    val next = bytes.peek() ?: break
                    if (next == END) break
                    buffer[offset + count++] = bytes.take().toByte()
                }
                return count
            }
        }

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = 0

    override fun exitValue(): Int = 0

    private companion object {
        const val END = -1
    }
}

/** Hands out [FakeLogcat]s in order, and records the arguments of each launch. */
internal class FakeLauncher(vararg processes: FakeLogcat) : (List<String>) -> Process {
    private val queue = LinkedBlockingQueue(processes.toList())
    val launches = LinkedBlockingQueue<List<String>>()

    fun add(process: FakeLogcat) = queue.put(process)

    override fun invoke(arguments: List<String>): Process {
        launches.put(arguments)
        return queue.poll() ?: FakeLogcat().emit("logcat: no more fakes").end()
    }

    fun nextLaunch(): List<String> = launches.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("logcat was not launched")
}
