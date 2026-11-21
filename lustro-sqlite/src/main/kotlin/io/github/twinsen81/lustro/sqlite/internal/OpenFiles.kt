package io.github.twinsen81.lustro.sqlite.internal

import android.system.ErrnoException
import android.system.Os
import java.io.File

/** The files that the process held open at one moment. */
internal fun interface OpenFiles {
    fun holds(file: File): Boolean

    companion object {
        private val DESCRIPTORS = File("/proc/self/fd")

        /**
         * The files of the process's descriptors now, by device and inode: one file can have two
         * paths, such as `/data/user/0/<package>` and `/data/data/<package>`. Without `/proc`, as
         * on a JVM outside Android, none.
         */
        fun ofProcess(): OpenFiles {
            val held = DESCRIPTORS.list()?.mapNotNullTo(HashSet()) { id(File(DESCRIPTORS, it)) } ?: return OpenFiles { false }
            return OpenFiles { file -> id(file) in held }
        }

        // stat() follows the link of a descriptor to its file.
        private fun id(file: File): FileId? =
            try {
                Os.stat(file.path).let { FileId(it.st_dev, it.st_ino) }
            } catch (_: ErrnoException) {
                // A descriptor closed since the listing, such as the listing's own, or a file that is gone.
                null
            }
    }
}

private data class FileId(val device: Long, val inode: Long)
