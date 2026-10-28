package io.github.twinsen81.lustro.work.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExecutionEventListener
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ScheduleEventListener
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.work.internal.WorkFixtures.json
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkListenersTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `the tab's listeners implement every method of WorkManager's listener interfaces`() {
        assertEquals(emptyList<String>(), WorkSupport.unknownMethods(ExecutionEventListener::class.java, ExecutionRecorder::class.java))
        assertEquals(emptyList<String>(), WorkSupport.unknownMethods(ScheduleEventListener::class.java, ScheduleRecorder::class.java))
    }

    @Test
    fun `a method that a newer interface adds, abstract or default, is one the wrapper does not forward`() {
        assertEquals(listOf("onNew", "onNewDefault"), WorkSupport.unknownMethods(NewerListener::class.java, OldWrapper::class.java))
    }

    @Test
    fun `configure on the same builder twice wraps the app's listeners once`() {
        val app = WorkFixtures.AppListeners()
        val source = WorkFixtures.source(this.app)
        val builder = Configuration.Builder().setExecutionEventListener(app).setScheduleEventListener(app)
        source.configure(builder)
        source.configure(builder)
        val built = builder.build()
        val execution = built.getExecutionEventListener() as ExecutionRecorder
        assertSame(app, execution.app)
        assertSame(app, (built.getScheduleEventListener() as ScheduleRecorder).app)

        runBlocking { execution.onStarted(info()) }
        assertEquals(1, source.events.snapshot(null).total)
        assertEquals(1, app.seen.size)
    }

    @Test
    fun `the tab keeps a listener that the app set, and adds one where the app set none`() {
        val app = WorkFixtures.AppListeners()
        val source = WorkFixtures.source(this.app)
        val built = source.configure(Configuration.Builder().setExecutionEventListener(app)).build()
        assertSame(app, (built.getExecutionEventListener() as ExecutionRecorder).app)
        assertEquals(null, (built.getScheduleEventListener() as ScheduleRecorder).app)
    }

    @Test
    fun `an event that the tab fails to record still reaches the app's listener`() {
        val app = WorkFixtures.AppListeners()
        val source = WorkFixtures.source(this.app, clock = { throw IllegalStateException("no clock") })
        val built = source.configure(Configuration.Builder().setExecutionEventListener(app)).build()
        runBlocking { built.getExecutionEventListener()!!.onStarted(info()) }
        assertEquals(1, app.seen.size)
        assertEquals(0, source.events.snapshot(null).total)
    }

    @Test
    fun `what the app's listener throws reaches WorkManager, after the tab recorded the event`() {
        val source = WorkFixtures.source(this.app)
        val failing =
            object : ExecutionEventListener {
                override suspend fun onStarted(workInfo: WorkInfo): Unit = throw IllegalArgumentException("the app's bug")

                override suspend fun onStopped(stopReason: Int, workInfo: WorkInfo) = Unit

                override suspend fun onFinished(result: androidx.work.ListenableWorker.Result, workInfo: WorkInfo) = Unit

                override suspend fun onException(throwable: Throwable, workInfo: WorkInfo) = Unit
            }
        val built = source.configure(Configuration.Builder().setExecutionEventListener(failing)).build()
        val thrown = runCatching { runBlocking { built.getExecutionEventListener()!!.onStarted(info()) } }.exceptionOrNull()
        assertEquals("the app's bug", thrown?.message)
        assertEquals(1, source.events.snapshot(null).total)
    }

    @Test
    fun `with the listeners installed through a real WorkManager, the state says installed`() {
        val source = WorkFixtures.source(app)
        WorkFixtures.initialize(app, source)
        WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<StepWorker>().build()).result.get()
        val state = get(WorkRoutes(source), "events").json().getJSONObject("state")
        assertEquals("installed", state.getString("listeners"))
        assertTrue(state.getBoolean("initialized"))
    }

    private fun get(routes: WorkRoutes, path: String): DebugResponse = routes.handle(DebugRequest(path, "GET"))!!

    private fun info(): WorkInfo = WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, setOf("t"))
}
