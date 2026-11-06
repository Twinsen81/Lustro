package io.github.twinsen81.lustro.work.internal

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What WorkManager's database has of each work request and `WorkInfo` does not: the
 * unique name that the work was enqueued with, and the work that it waits for, the
 * work before it in a chain. The tab reads them through WorkManager's own connection,
 * with the DAO calls that read the `WorkName` and `Dependency` tables. They are
 * WorkManager's internals: when the app's WorkManager does not have those calls, or the
 * read fails, the tab knows only the names that the app registered, and no
 * prerequisites.
 */
internal class WorkRecords(private val registered: List<String>) {
    /** The unique name of a work request, if it has one, and the ids of the work that it waits for, if any. */
    class Record(val uniqueName: String?, val prerequisites: List<String>) {
        companion object {
            val NONE: Record = Record(null, emptyList())
        }
    }

    // A work request keeps the name and the prerequisites that it was enqueued with, so
    // each id is read once.
    private val known = ConcurrentHashMap<UUID, Record>()

    @Volatile
    private var fromDatabase = true

    /** The record of each work in [infos], by id. Work without a name or prerequisites is not in the map. */
    fun of(workManager: WorkManager, infos: List<WorkInfo>): Map<UUID, Record> {
        if (fromDatabase) {
            val records = fromDatabase(workManager, infos)
            if (records != null) return records
        }
        return fromRegistered(workManager)
    }

    // By reflection: WorkManagerImpl, WorkDatabase, and its DAOs are restricted to the
    // androidx.work group, and WorkDatabase is a Room database, which is not on the tab's
    // compile classpath. A WorkManager that changed them turns this path off.
    private fun fromDatabase(workManager: WorkManager, infos: List<WorkInfo>): Map<UUID, Record>? =
        try {
            val database = WORK_DATABASE.invoke(workManager)
            val names = WORK_NAME_DAO.invoke(database)
            val dependencies = DEPENDENCY_DAO.invoke(database)
            if (known.size > MAX_KNOWN) known.clear()
            val records = HashMap<UUID, Record>()
            for (info in infos) {
                val record = known.getOrPut(info.id) { read(names, dependencies, info.id.toString()) }
                if (record !== Record.NONE) records[info.id] = record
            }
            records
        } catch (e: InvocationTargetException) {
            // A read that failed once, such as for a database that WorkManager is closing,
            // can work at the next poll.
            Log.w(WorkEvents.TAG, "Could not read the unique names and prerequisites of the work", e.cause ?: e)
            null
        } catch (e: ReflectiveOperationException) {
            off(e)
        } catch (e: LinkageError) {
            off(e)
        } catch (e: ClassCastException) {
            off(e)
        } catch (e: IllegalArgumentException) {
            // The app's WorkManager is not WorkManagerImpl, such as a test double.
            off(e)
        }

    private fun read(names: Any?, dependencies: Any?, id: String): Record {
        val name = (NAMES_FOR_ID.invoke(names, id) as List<*>).map { it.toString() }.minOrNull()
        val prerequisites = (PREREQUISITES.invoke(dependencies, id) as List<*>).map { it.toString() }.sorted()
        return if (name == null && prerequisites.isEmpty()) Record.NONE else Record(name, prerequisites)
    }

    private fun off(e: Throwable): Map<UUID, Record>? {
        fromDatabase = false
        Log.w(
            WorkEvents.TAG,
            "The tab can't read the unique names and prerequisites from WorkManager's database ($e). " +
                "It labels the work of uniqueNames only, and shows no prerequisites.",
        )
        return null
    }

    // Each registered name is one query.
    private fun fromRegistered(workManager: WorkManager): Map<UUID, Record> {
        val records = HashMap<UUID, Record>()
        for (name in registered) {
            for (info in workManager.getWorkInfosForUniqueWork(name).get()) records[info.id] = Record(name, emptyList())
        }
        return records
    }

    private companion object {
        const val MAX_KNOWN = 10_000

        // Looked up at the first use: the class loads only for an app that has WorkManager.
        val WORK_DATABASE: Method by lazy { Class.forName("androidx.work.impl.WorkManagerImpl").getMethod("getWorkDatabase") }
        val WORK_NAME_DAO: Method by lazy { Class.forName("androidx.work.impl.WorkDatabase").getMethod("workNameDao") }
        val DEPENDENCY_DAO: Method by lazy { Class.forName("androidx.work.impl.WorkDatabase").getMethod("dependencyDao") }
        val NAMES_FOR_ID: Method by lazy {
            Class.forName("androidx.work.impl.model.WorkNameDao").getMethod("getNamesForWorkSpecId", String::class.java)
        }
        val PREREQUISITES: Method by lazy {
            Class.forName("androidx.work.impl.model.DependencyDao").getMethod("getPrerequisites", String::class.java)
        }
    }
}
