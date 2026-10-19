package io.github.twinsen81.lustro.logs.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class LogcatReaderTest {
    private val utc = TimeZone.getTimeZone("UTC")

    // 2026-10-19 14:22:20.010 UTC
    private val now = 1_792_419_740_010L
    private val buffer = LogBuffer(1 shl 20)
    private val launcher = FakeLauncher()
    private val reader =
        LogcatReader(
            buffer,
            pid = 4321,
            processStartMs = { now - 60_000 },
            launch = launcher,
            restartDelayMs = 10,
            parser = ThreadtimeParser(utc) { now },
        )

    @After
    fun tearDown() {
        reader.stop()
    }

    @Test
    fun `the first run reads this process from its start, and its lines go to the buffer`() {
        val logcat = FakeLogcat()
        launcher.add(logcat)
        reader.start()

        assertEquals(listOf("logcat", "-v", "threadtime", "-v", "UTC", "--pid=4321", "-T", "1792419680.010"), launcher.nextLaunch())
        logcat.emit(
            "--------- beginning of main",
            "10-19 14:22:20.000  4321  4330 I App     : started",
            "10-19 14:22:20.005  4321  4330 W App     : careful",
        )
        awaitMessages("started", "careful")
        assertEquals(LogcatReader.Status.RUNNING, reader.status)
    }

    @Test
    fun `lines from before the process started are skipped, as logcat on API 24 prints them all`() {
        val logcat = FakeLogcat()
        launcher.add(logcat)
        reader.start()
        launcher.nextLaunch()

        logcat.emit(
            "10-19 14:20:00.000  4321  4330 I App     : an earlier process with this pid",
            "10-19 14:22:20.000  4321  4330 I App     : this process",
        )
        awaitMessages("this process")
    }

    @Test
    fun `a logcat that rejects the time of -T is run without it`() {
        launcher.add(FakeLogcat().emit("-T \"1792419680.010\" not in time format").end())
        val second = FakeLogcat()
        launcher.add(second)
        reader.start()

        assertTrue(launcher.nextLaunch().contains("-T"))
        assertEquals(listOf("logcat", "-v", "threadtime", "-v", "UTC", "--pid=4321"), launcher.nextLaunch())
        second.emit("10-19 14:22:20.000  4321  4330 I App     : read")
        awaitMessages("read")
    }

    @Test
    fun `stop ends logcat and the thread, and the next start goes on after the newest line`() {
        val first = FakeLogcat()
        launcher.add(first)
        reader.start()
        launcher.nextLaunch()
        first.emit(
            "10-19 14:22:20.000  4321  4330 I App     : one",
            "10-19 14:22:20.005  4321  4330 I App     : two",
            "10-19 14:22:20.005  4321  4330 I App     : three",
        )
        awaitMessages("one", "two", "three")

        reader.stop()
        assertTrue(first.destroyed)
        assertEquals(LogcatReader.Status.STOPPED, reader.status)
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "lustro-logcat" && it.isAlive })

        val second = FakeLogcat()
        launcher.add(second)
        reader.start()
        assertEquals("1792419740.005", launcher.nextLaunch().last())
        // logcat repeats the lines at the time it starts from: those two were read.
        second.emit(
            "10-19 14:22:20.000  4321  4330 I App     : one",
            "10-19 14:22:20.005  4321  4330 I App     : two",
            "10-19 14:22:20.005  4321  4330 I App     : three",
            "10-19 14:22:20.005  4321  4330 I App     : four",
            "10-19 14:22:20.007  4321  4330 I App     : five",
        )
        awaitMessages("one", "two", "three", "four", "five")
    }

    @Test
    fun `after a run that read lines ends, the reader starts logcat again from the newest line`() {
        launcher.add(FakeLogcat().emit("10-19 14:22:20.000  4321  4330 I App     : one").end())
        val second = FakeLogcat()
        launcher.add(second)
        reader.start()

        launcher.nextLaunch()
        assertEquals("1792419740.000", launcher.nextLaunch().last())
        second.emit("10-19 14:22:21.000  4321  4330 I App     : two")
        awaitMessages("one", "two")
        assertEquals(LogcatReader.Status.RUNNING, reader.status)
    }

    @Test
    fun `logcat that ends with no line four times in a row fails the reader, with logcat's last words`() {
        repeat(4) { launcher.add(FakeLogcat().emit("logcat: unknown option --pid=4321").end()) }
        launcher.add(FakeLogcat())
        reader.start()

        repeat(4) { launcher.nextLaunch() }
        awaitStatus(LogcatReader.Status.FAILED)
        assertEquals("logcat ended: logcat: unknown option --pid=4321", reader.error)
        Thread.sleep(50)
        assertTrue("no fifth launch", launcher.launches.isEmpty())
    }

    @Test
    fun `a logcat that can't start counts as a failure`() {
        val reader =
            LogcatReader(buffer, pid = 1, processStartMs = { now }, launch = { throw java.io.IOException("no such file") }, restartDelayMs = 1)
        reader.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (reader.status != LogcatReader.Status.FAILED && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(LogcatReader.Status.FAILED, reader.status)
        assertEquals("logcat could not start: no such file", reader.error)
    }

    @Test
    fun `a paused buffer drops the lines that the reader goes on reading`() {
        val logcat = FakeLogcat()
        launcher.add(logcat)
        reader.start()
        launcher.nextLaunch()

        buffer.paused = true
        logcat.emit("10-19 14:22:20.000  4321  4330 I App     : while paused")
        val deadline = System.currentTimeMillis() + 5_000
        while (reader.newestLineAt != now - 10 && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(now - 10, reader.newestLineAt)
        buffer.paused = false
        logcat.emit("10-19 14:22:20.001  4321  4330 I App     : after")
        awaitMessages("after")
    }

    private fun awaitMessages(vararg expected: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var messages: List<String>
        do {
            val snapshot = buffer.snapshot(null, issuesCursor = false)
            messages = (snapshot.first until snapshot.retained).mapNotNull { snapshot[it]?.message }
            if (messages == expected.toList()) return
            Thread.sleep(5)
        } while (System.currentTimeMillis() < deadline)
        assertEquals(expected.toList(), messages)
    }

    private fun awaitStatus(expected: LogcatReader.Status) {
        val deadline = System.currentTimeMillis() + 5_000
        while (reader.status != expected && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals(expected, reader.status)
    }
}
