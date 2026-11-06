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
 * WorkManager's internals, so each read turns itself off when the app's WorkManager
 * does not have its call: without the names, the tab knows only the names that the
 * app registered, and without the prerequisites, it shows none.
 */
internal class WorkRecords(
    private val registered: List<String>,
    private val database: (WorkManager) -> Any? = { WORK_DATABASE.invoke(it) },
    private val names: DaoRead = DaoRead("unique names", { WORK_NAME_DAO.invoke(it) }) { dao, id -> NAMES_FOR_ID.invoke(dao, id) },
    private val prerequisites: DaoRead = DaoRead("prerequisites", { DEPENDENCY_DAO.invoke(it) }) { dao, id -> PREREQUISITES.invoke(dao, id) },
) {
    /** The unique name of a work request, if it has one, and the ids of the work that it waits for, if any. */
    class Record(val uniqueName: String?, val prerequisites: List<String>)

    /**
     * One DAO call that returns a list of strings for a work id, read once for each id:
     * a work request keeps the name and the prerequisites that it was enqueued with.
     */
    class DaoRead(private val what: String, private val dao: (Any?) -> Any?, private val call: (Any?, String) -> Any?) {
        private val known = ConcurrentHashMap<UUID, List<String>>()

        @Volatile
        var on: Boolean = true
            private set

        /** The values of each work in [infos], by id, or `null` when the read is off or failed. */
        fun of(database: Any?, infos: List<WorkInfo>): Map<UUID, List<String>>? {
            if (!on) return null
            return try {
                val dao = dao(database)
                if (known.size > MAX_KNOWN) known.clear()
                infos.associate { info -> info.id to known.getOrPut(info.id) { (call(dao, info.id.toString()) as List<*>).map { it.toString() } } }
            } catch (e: InvocationTargetException) {
                // A read that failed once, such as for a database that WorkManager is closing,
                // can work at the next poll.
                Log.w(WorkEvents.TAG, "Could not read the $what of the work", e.cause ?: e)
                null
            } catch (e: ReflectiveOperationException) {
                off(e)
            } catch (e: LinkageError) {
                off(e)
            } catch (e: ClassCastException) {
                off(e)
            } catch (e: IllegalArgumentException) {
                off(e)
            }
        }

        fun off(e: Throwable): Map<UUID, List<String>>? {
            on = false
            Log.w(WorkEvents.TAG, "The tab can't read the $what from WorkManager's database ($e)")
            return null
        }
    }

    /** The record of each work in [infos], by id. Work without a name or prerequisites is not in the map. */
    fun of(workManager: WorkManager, infos: List<WorkInfo>): Map<UUID, Record> {
        val db = if (names.on || prerequisites.on) open(workManager) else UNREACHABLE
        val nameLists = if (db === UNREACHABLE) null else names.of(db, infos)
        val prerequisiteLists = if (db === UNREACHABLE) null else prerequisites.of(db, infos)
        val uniqueNames = nameLists?.mapNotNull { (id, list) -> list.minOrNull()?.let { id to it } }?.toMap() ?: fromRegistered(workManager)
        val records = HashMap<UUID, Record>()
        for (info in infos) {
            val name = uniqueNames[info.id]
            val waitsFor = prerequisiteLists?.get(info.id).orEmpty().sorted()
            if (name != null || waitsFor.isNotEmpty()) records[info.id] = Record(name, waitsFor)
        }
        return records
    }

    // WorkManager's database, or UNREACHABLE. An app's WorkManager that is not one whose
    // database the tab can reach turns both reads off.
    private fun open(workManager: WorkManager): Any? =
        try {
            database(workManager)
        } catch (e: InvocationTargetException) {
            Log.w(WorkEvents.TAG, "Could not open WorkManager's database", e.cause ?: e)
            UNREACHABLE
        } catch (e: ReflectiveOperationException) {
            offBoth(e)
        } catch (e: LinkageError) {
            offBoth(e)
        } catch (e: IllegalArgumentException) {
            // The app's WorkManager is not WorkManagerImpl, such as a test double.
            offBoth(e)
        }

    private fun offBoth(e: Throwable): Any {
        if (names.on) names.off(e)
        if (prerequisites.on) prerequisites.off(e)
        return UNREACHABLE
    }

    // Each registered name is one query.
    private fun fromRegistered(workManager: WorkManager): Map<UUID, String> {
        val found = HashMap<UUID, String>()
        for (name in registered) {
            for (info in workManager.getWorkInfosForUniqueWork(name).get()) found[info.id] = name
        }
        return found
    }

    private companion object {
        const val MAX_KNOWN = 10_000
        val UNREACHABLE = Any()

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
