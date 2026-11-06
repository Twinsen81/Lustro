package io.github.twinsen81.lustro.work.internal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reads of WorkManager's internals turn themselves off when the app's WorkManager
 * does not have their calls, each on its own: a WorkManager that changed one DAO keeps
 * the other read.
 */
@RunWith(RobolectricTestRunner::class)
class WorkRecordsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var workManager: WorkManager
    private lateinit var first: OneTimeWorkRequest
    private lateinit var second: OneTimeWorkRequest

    @Before
    fun setUp() {
        WorkFixtures.initialize(app, WorkFixtures.source(app))
        workManager = WorkManager.getInstance(app)
        // The first step waits for its delay, which the test driver never meets, so the second stays blocked.
        first = OneTimeWorkRequestBuilder<StepWorker>().setInitialDelay(1, TimeUnit.HOURS).build()
        second = OneTimeWorkRequestBuilder<StepWorker>().build()
        workManager.beginUniqueWork("download", ExistingWorkPolicy.KEEP, first).then(second).enqueue().result.get()
        workManager.enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<StepWorker>().build()).result.get()
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `both reads find the names and the prerequisites in WorkManager's database`() {
        val records = WorkRecords(registered = emptyList()).of(workManager, infos())

        assertEquals("download", records.getValue(second.id).uniqueName)
        assertEquals(listOf(first.id.toString()), records.getValue(second.id).prerequisites)
        assertEquals(emptyList<String>(), records.getValue(first.id).prerequisites)
    }

    @Test
    fun `without the call of the prerequisites, the names still come from the database`() {
        val prerequisites = WorkRecords.DaoRead("prerequisites", { throw NoSuchMethodException("dependencyDao") }) { _, _ -> emptyList<String>() }
        val records = WorkRecords(registered = emptyList(), prerequisites = prerequisites)

        val found = records.of(workManager, infos())

        // "download" is no registered name: only the database has it.
        assertEquals("download", found.getValue(second.id).uniqueName)
        assertEquals(emptyList<String>(), found.getValue(second.id).prerequisites)
        assertFalse(prerequisites.on)
        // The read stays off: the next poll does not try the call again.
        assertEquals("download", records.of(workManager, infos()).getValue(second.id).uniqueName)
    }

    @Test
    fun `a WorkManager whose database the tab can't reach gives the registered names only, and no prerequisites`() {
        // As for a WorkManager that is not WorkManagerImpl, such as a test double.
        val records = WorkRecords(registered = listOf("sync"), database = { throw IllegalArgumentException("not WorkManagerImpl") })

        val found = records.of(workManager, infos())

        assertNull(found[second.id])
        assertNull(found[first.id])
        val sync = found.values.single()
        assertEquals("sync", sync.uniqueName)
        assertTrue(sync.prerequisites.isEmpty())
    }

    private fun infos(): List<WorkInfo> = workManager.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.entries)).get()
}
