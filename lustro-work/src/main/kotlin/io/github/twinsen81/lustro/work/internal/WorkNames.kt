package io.github.twinsen81.lustro.work.internal

import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The unique name of each work request. `WorkInfo` has none, so the tab reads it
 * from WorkManager's own database, through WorkManager's own connection: the
 * `WorkName` table and the DAO call that reads it have been the same since
 * WorkManager 2.0. When the app's WorkManager does not have that call, or the read
 * fails, the tab knows only the names that the app registered.
 */
internal class WorkNames(private val registered: List<String>) {
    // A work request keeps the name that it was enqueued with, so each id is read once.
    // NONE stands for work without a name.
    private val known = ConcurrentHashMap<UUID, String>()

    @Volatile
    private var fromDatabase = true

    /** The unique names of the work in [infos], by id. Work without a name is not in the map. */
    fun of(workManager: WorkManager, infos: List<WorkInfo>): Map<UUID, String> {
        if (fromDatabase) {
            val names = fromDatabase(workManager, infos)
            if (names != null) return names
        }
        return fromRegistered(workManager)
    }

    // By reflection: WorkManagerImpl, WorkDatabase, and WorkNameDao are restricted to
    // the androidx.work group, and WorkDatabase is a Room database, which is not on the
    // tab's compile classpath. A WorkManager that changed them turns this path off.
    private fun fromDatabase(workManager: WorkManager, infos: List<WorkInfo>): Map<UUID, String>? =
        try {
            val database = WORK_DATABASE.invoke(workManager)
            val dao = WORK_NAME_DAO.invoke(database)
            if (known.size > MAX_KNOWN) known.clear()
            val names = HashMap<UUID, String>()
            for (info in infos) {
                val name =
                    known.getOrPut(info.id) {
                        (NAMES_FOR_ID.invoke(dao, info.id.toString()) as List<*>).map { it.toString() }.minOrNull() ?: NONE
                    }
                if (name != NONE) names[info.id] = name
            }
            names
        } catch (e: InvocationTargetException) {
            // A read that failed once, such as for a database that WorkManager is closing,
            // can work at the next poll.
            Log.w(WorkEvents.TAG, "Could not read the unique names of the work", e.cause ?: e)
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

    private fun off(e: Throwable): Map<UUID, String>? {
        fromDatabase = false
        Log.w(WorkEvents.TAG, "The tab can't read the unique names from WorkManager's database ($e). It labels the work of uniqueNames only.")
        return null
    }

    // Each registered name is one query.
    private fun fromRegistered(workManager: WorkManager): Map<UUID, String> {
        val names = HashMap<UUID, String>()
        for (name in registered) {
            for (info in workManager.getWorkInfosForUniqueWork(name).get()) names[info.id] = name
        }
        return names
    }

    private companion object {
        const val NONE = ""
        const val MAX_KNOWN = 10_000

        // Looked up at the first use: the class loads only for an app that has WorkManager.
        val WORK_DATABASE: Method by lazy { Class.forName("androidx.work.impl.WorkManagerImpl").getMethod("getWorkDatabase") }
        val WORK_NAME_DAO: Method by lazy { Class.forName("androidx.work.impl.WorkDatabase").getMethod("workNameDao") }
        val NAMES_FOR_ID: Method by lazy {
            Class.forName("androidx.work.impl.model.WorkNameDao").getMethod("getNamesForWorkSpecId", String::class.java)
        }
    }
}
