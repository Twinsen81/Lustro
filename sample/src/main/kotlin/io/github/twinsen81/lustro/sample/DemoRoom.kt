package io.github.twinsen81.lustro.sample

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The sample's Room database: a few tasks, which the SQLite screen shows through a
 * DAO's Flow. Room's observers look for changes after Room's own transactions only,
 * so the debug bootstrap registers it with the SQLite tab's onWrite hook, which
 * calls InvalidationTracker.refreshVersionsAsync() after each write of the tab: an
 * edit in the console then shows on the open screen at once.
 */
@Database(entities = [DemoRoom.Task::class], version = 1, exportSchema = false)
internal abstract class DemoRoom : RoomDatabase() {
    abstract fun tasks(): TaskDao

    @Entity(tableName = "tasks")
    internal data class Task(
        @PrimaryKey(autoGenerate = true) val id: Long = 0,
        val title: String,
        val done: Boolean = false,
    )

    @Dao
    internal interface TaskDao {
        @Query("SELECT * FROM tasks ORDER BY id")
        fun all(): Flow<List<Task>>

        @Query("SELECT COUNT(*) FROM tasks")
        fun count(): Long

        @Insert
        fun insert(tasks: List<Task>)
    }

    companion object {
        /** The registered name of the Room database in the SQLite tab. */
        const val NAME: String = "room"
        const val FILE: String = "sample_room.db"

        @Volatile
        private var instance: DemoRoom? = null

        /** The app's one instance, which writes the demo tasks on its first open. */
        fun get(context: Context): DemoRoom =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, DemoRoom::class.java, FILE)
                    .build()
                    .also { instance = it }
            }

        /** Writes the demo tasks when the table is empty. Call it off the main thread. */
        fun seed(context: Context) {
            val tasks = get(context).tasks()
            if (tasks.count() > 0) return
            tasks.insert(listOf(Task(title = "Buy milk"), Task(title = "Call the bank"), Task(title = "Book the trip", done = true)))
        }
    }
}
