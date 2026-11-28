package com.jerrey.monoicon.theme.archive

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.TimeUnit

/** Fixed root entry point, launched by name through app_process. */
@Keep
object RootThemeArchive {
    @JvmStatic
    fun main(args: Array<String>) {
        val answer = try {
            require(Process.myUid() == 0)
            val verb = args.single()
            require(verb in setOf("full", "update", "restore", "status", "accept", "recoverable", "recover"))
            val themeDir = File("/data/system/theme")
            require(themeDir.canonicalFile == themeDir && themeDir.isDirectory) { "HyperOS theme directory unavailable" }
            val stateDir = File("/data/system/monoicon-theme")
            Os.umask(63) // 0077
            try {
                Os.mkdir(stateDir.path, 448) // 0700
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EEXIST) throw e
            }
            val stat = Os.lstat(stateDir.path)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == 0 && (stat.st_mode and 511) == 448)
            val lock = File(stateDir, "lock")
            require(!Files.exists(lock.toPath(), NOFOLLOW_LINKS) || Files.isRegularFile(lock.toPath(), NOFOLLOW_LINKS))
            RandomAccessFile(lock, "rw").use { file ->
                file.channel.lock().use {
                    // Persist the directory entry before the first journal can protect a rename.
                    if (!File(stateDir, "state-v1").exists()) syncDirectory(checkNotNull(stateDir.parentFile))
                    val store = ThemeArchiveStore(File(themeDir, "icons"), stateDir, ::preserveAttributes,
                        ::syncDirectory, { refreshIcons(stateDir) }, ::readDefaultIcon, ::writeDefaultIcon)
                    store.cleanInterruptedWork()
                    when (verb) {
                        "status" -> store.status().name
                        "accept" -> {
                            store.acceptCurrentTheme()
                            "OK"
                        }
                        "restore" -> encode(store.restore())
                        "recoverable" -> if (store.canRecoverMissingOriginal()) "1" else "0"
                        "recover" -> encode(store.recoverMissingOriginal())
                        else -> {
                            val patch = File.createTempFile("patch-", ".zip", stateDir)
                            try {
                                patch.outputStream().use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    var total = 0L
                                    while (true) {
                                        val count = System.`in`.read(buffer)
                                        if (count == -1) break
                                        total += count
                                        require(total <= ThemeIconZip.MAX_ARCHIVE)
                                        out.write(buffer, 0, count)
                                    }
                                }
                                encode(store.apply(patch, verb == "full"))
                            } finally {
                                patch.delete()
                            }
                        }
                    }
                }
            }
        } catch (_: ThemeArchiveConflictException) {
            "EXTERNAL"
        } catch (e: Exception) {
            "ERROR " + (e.message ?: e.javaClass.simpleName).lineSequence().firstOrNull().orEmpty().take(300)
        }
        println(ThemeArchiveInstaller.MARKER + answer)
    }

    private fun encode(result: ThemeArchiveResult) =
        "${if (result.changed) 1 else 0} ${if (result.refreshSucceeded) 1 else 0}"

    // HyperOS Theme Manager uses 1 for a third-party icon module. The launcher can then
    // retain each PNG's own alpha outline when handing the returning app back to its icon.
    private const val DEFAULT_ICON = "is_default_icon"

    private fun readDefaultIcon(): String? = when (val value = iconSetting("get", "global", DEFAULT_ICON)) {
        "null" -> null
        "0", "1" -> value
        else -> throw IOException("Unexpected $DEFAULT_ICON value: ${value.take(200)}")
    }

    private fun writeDefaultIcon(value: String?) {
        if (value == null) iconSetting("delete", "global", DEFAULT_ICON)
        else iconSetting("put", "global", DEFAULT_ICON, value)
    }

    private fun iconSetting(vararg arguments: String): String {
        val process = ProcessBuilder("/system/bin/settings", *arguments).redirectErrorStream(true).start()
        try {
            process.outputStream.close()
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw IOException("Timed out accessing $DEFAULT_ICON")
            val output = process.inputStream.use { it.readNBytes(512).decodeToString().trim() }
            if (process.exitValue() != 0) {
                throw IOException("Cannot access $DEFAULT_ICON: ${output.take(200)}")
            }
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun preserveAttributes(reference: File, staged: File) {
        val attrs = Os.lstat(reference.path)
        val mode = if (OsConstants.S_ISDIR(attrs.st_mode)) 420 /* 0644 */ else attrs.st_mode and 511
        val labelKey = "security.selinux"
        val label = Os.getxattr(reference.path, labelKey)
        require(label.isNotEmpty()) { "Cannot identify the system theme's SELinux label" }
        Os.chown(staged.path, attrs.st_uid, attrs.st_gid)
        Os.chmod(staged.path, mode)
        if (!Os.getxattr(staged.path, labelKey).contentEquals(label)) {
            Os.setxattr(staged.path, labelKey, label, 0)
        }
        val applied = Os.lstat(staged.path)
        check(applied.st_uid == attrs.st_uid && applied.st_gid == attrs.st_gid &&
            (applied.st_mode and 511) == mode && Os.getxattr(staged.path, labelKey).contentEquals(label)) {
            "Unable to match active theme permissions or SELinux label"
        }
    }

    private fun syncDirectory(dir: File) {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }

    /** Publish the icon module flag through the same framework configuration that theme consumers
     * observe. Failure leaves a durable pending refresh that the next apply/restore retries. */
    private fun refreshIcons(stateDir: File): Boolean = ThemeIconRefresh.request(
        broadcast = { broadcastThemeChange(stateDir) },
        warn = { operation, error ->
            val detail = error.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(240)
            println(ThemeArchiveInstaller.WARNING + "$operation: ${error.javaClass.simpleName} $detail")
        },
    )

    private fun broadcastThemeChange(stateDir: File) {
        val output = File.createTempFile("broadcast-", ".txt", stateDir)
        try {
            val broadcast = ProcessBuilder("/system/bin/am", "broadcast", "--user", "0", "-a",
                "miui.intent.action.ACTION_THEME_CHANGED").redirectErrorStream(true)
                .redirectOutput(output).start()
            try {
                if (!broadcast.waitFor(15, TimeUnit.SECONDS)) throw IOException("Timed out after 15 s")
                if (broadcast.exitValue() != 0) {
                    val detail = output.inputStream().use { it.readNBytes(512).decodeToString().trim().replace('\n', ' ') }
                    throw IOException("am exit ${broadcast.exitValue()}: $detail")
                }
            } finally {
                if (broadcast.isAlive) broadcast.destroyForcibly()
            }
        } finally {
            output.delete()
        }
    }
}
