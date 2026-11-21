package io.github.twinsen81.lustro.sqlite.internal

import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenFilesTest {
    private val descriptors = Files.createTempDirectory("fd").toFile().apply { deleteOnExit() }

    @Test
    fun `a file held open matches by device and inode, through each of its paths`() {
        descriptor("3", "4", "5")
        val stats =
            mapOf(
                "${descriptors.path}/3" to stat(device = 1, inode = 42),
                "${descriptors.path}/5" to stat(device = 1, inode = 0),
                "/data/user/0/app/databases/a.db" to stat(device = 1, inode = 42),
                "/data/data/app/databases/a.db" to stat(device = 1, inode = 42),
                "/data/data/app/databases/b.db" to stat(device = 1, inode = 43),
                "/mnt/other/a.db" to stat(device = 2, inode = 42),
                "/data/data/app/databases/unknown.db" to stat(device = 1, inode = 0),
            )

        // Descriptor 4 was closed after the listing: its stat() fails, and it is left out.
        val open = OpenFiles.ofProcess(descriptors) { path -> stats[path] ?: throw ErrnoException("stat", OsConstants.ENOENT) }

        assertTrue(open.holds(File("/data/user/0/app/databases/a.db")))
        assertTrue(open.holds(File("/data/data/app/databases/a.db")))
        assertFalse(open.holds(File("/data/data/app/databases/b.db")))
        assertFalse(open.holds(File("/mnt/other/a.db")))
        // Inode 0 names no file, also when a descriptor gives it too.
        assertFalse(open.holds(File("/data/data/app/databases/unknown.db")))
        assertFalse(open.holds(File("/data/data/app/databases/gone.db")))
    }

    @Test
    fun `without the descriptors directory, no file is held open`() {
        val stats = mutableListOf<String>()

        val open = OpenFiles.ofProcess(File(descriptors, "missing")) { path -> stats += path; stat(device = 1, inode = 42) }

        assertFalse(open.holds(File("/data/data/app/databases/a.db")))
        assertEquals(emptyList<String>(), stats)
    }

    @Test
    fun `under Robolectric, whose stat() gives no inode, no file is held open on any host`() {
        val file = File(descriptors, "app.db").apply { writeText("x") }

        assertFalse(OpenFiles.ofProcess().holds(file))
    }

    private fun descriptor(vararg names: String) = names.forEach { File(descriptors, it).createNewFile() }

    private fun stat(device: Long, inode: Long) = StructStat(device, inode, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0)
}
