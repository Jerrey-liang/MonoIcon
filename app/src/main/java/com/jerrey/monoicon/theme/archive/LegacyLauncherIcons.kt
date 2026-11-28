package com.jerrey.monoicon.theme.archive

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Retire the two historical activation records while an old launcher process may still be
 * running. Its existing worker observes the change. Keep its resources and safety state intact. */
internal fun retireLegacyLauncherIcons(
    deviceFiles: File = File("/data/user_de/0/com.miui.home/files"),
    credentialFiles: File = File("/data/user/0/com.miui.home/files"),
) {
    for (directory in listOf(deviceFiles, credentialFiles)) {
        if (!directory.isDirectory) continue
        val parent = try {
            Os.open(directory.path, OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or OsConstants.O_RDONLY, 0)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ELOOP) continue
            throw e
        }
        try {
            val attributes = Os.fstat(parent)
            if (!OsConstants.S_ISDIR(attributes.st_mode)) continue
            // Pin the launcher-owned directory even if it is renamed during migration.
            ParcelFileDescriptor.dup(parent).use { handle ->
                val files = "/proc/self/fd/${handle.fd}"
                if (directory == deviceFiles) {
                    try {
                        val marker = "$files/monoicon_large_icons.active"
                        if (OsConstants.S_ISREG(Os.lstat(marker).st_mode)) Os.remove(marker)
                    } catch (e: ErrnoException) {
                        if (e.errno != OsConstants.ENOENT) throw e
                    }
                }
                disableLegacyPalette("$files/monoicon_palette.txt", attributes.st_uid)
            }
        } finally {
            Os.close(parent)
        }
    }
}

private fun disableLegacyPalette(path: String, launcherUid: Int) {
    val fd = try {
        if (!OsConstants.S_ISREG(Os.lstat(path).st_mode)) return
        Os.open(path, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ELOOP) return
        throw e
    }
    try {
        val stat = Os.fstat(fd)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != launcherUid ||
            stat.st_nlink != 1L || stat.st_size !in 13L..1024L) return
        // The older palette worker retains its last valid transaction when a file is removed.
        // Change just the enabled byte in its versioned record, preserving ownership and label.
        val header = ByteArray(13)
        if (Os.pread(fd, header, 0, header.size, 0) != header.size ||
            !header.decodeToString().matches(Regex("MONOICON [123] 1 "))) return
        check(Os.pwrite(fd, byteArrayOf('0'.code.toByte()), 0, 1, 11) == 1)
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}
