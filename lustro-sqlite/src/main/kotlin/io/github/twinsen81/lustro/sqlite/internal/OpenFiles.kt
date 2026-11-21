package io.github.twinsen81.lustro.sqlite.internal

import android.system.ErrnoException
import android.system.Os
import android.system.StructStat
import java.io.File

/** The files that the process held open at one moment. */
internal fun interface OpenFiles {
    fun holds(file: File): Boolean

    companion object {
        /**
         * The files of the process's [descriptors] now, by device and inode: one file can have
         * two paths, such as `/data/user/0/<package>` and `/data/data/<package>`. Without the
         * directory, as on a JVM outside Android, none.
         */
        fun ofProcess(descriptors: File = File("/proc/self/fd"), stat: (String) -> StructStat = Os::stat): OpenFiles {
            fun id(file: File): FileId? =
                try {
                    // stat() follows the link of a descriptor to its file. No file has inode 0: a
                    // stat() that gives it, such as Robolectric's, knows no inode.
                    stat(file.path).takeIf { it.st_ino != 0L }?.let { FileId(it.st_dev, it.st_ino) }
                } catch (_: ErrnoException) {
                    // A descriptor closed since the listing, such as the listing's own, or a file that is gone.
                    null
                }
            val held = descriptors.list()?.mapNotNullTo(HashSet()) { id(File(descriptors, it)) } ?: return OpenFiles { false }
            return OpenFiles { file -> id(file) in held }
        }
    }
}

private data class FileId(val device: Long, val inode: Long)
