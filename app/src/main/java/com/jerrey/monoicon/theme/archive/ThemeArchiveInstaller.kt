package com.jerrey.monoicon.theme.archive

import android.content.Context
import android.os.Process
import com.jerrey.monoicon.logging.logw
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Blocking module-process API. Only the installed APK path and fixed verb enter the root
 * command. Rendered images are passed as a ZIP on stdin, never as root-readable filenames. */
object ThemeArchiveInstaller {
    fun apply(context: Context, icons: Map<String, Map<String, File>>, full: Boolean): ThemeArchiveResult {
        val patch = File.createTempFile("monoicon-theme-", ".zip", context.cacheDir)
        return try {
            ThemeIconZip.writePatch(patch, icons)
            result(call(context, if (full) "full" else "update", patch))
        } finally {
            patch.delete()
        }
    }

    fun restore(context: Context): ThemeArchiveResult = result(call(context, "restore"))
    fun status(context: Context) = ThemeArchiveStatus.valueOf(call(context, "status"))
    fun acceptCurrentTheme(context: Context) { call(context, "accept") }
    fun canRecoverMissingOriginal(context: Context): Boolean = call(context, "recoverable") == "1"
    fun recoverMissingOriginal(context: Context): ThemeArchiveResult = result(call(context, "recover"))

    internal fun largeIconCatalog(context: Context): List<LargeIconResources.Asset> {
        val assets = mutableListOf<LargeIconResources.Asset>()
        val count = call(context, "large-list", inspect = { file ->
            check(file.length() <= 4L * 1024 * 1024)
            file.useLines { lines -> lines.filter { it.startsWith(ASSET) }.forEach {
                check(assets.size < LargeIconResources.MAX_FILES)
                assets += LargeIconPatch.parse(it.removePrefix(ASSET))
            } }
        }).toInt()
        check(count == assets.size)
        return assets
    }

    internal fun applyLargeIcons(context: Context, patch: File) = result(call(context, "large-apply", patch))
    internal fun restoreLargeIcons(context: Context) = result(call(context, "large-restore"))

    private fun result(value: String): ThemeArchiveResult {
        val flags = value.split(' ')
        if (flags.size != 2 || flags.any { it != "0" && it != "1" }) throw IOException("Invalid theme result")
        return ThemeArchiveResult(flags[0] == "1", flags[1] == "1")
    }

    private fun call(context: Context, verb: String, patch: File? = null, inspect: ((File) -> Unit)? = null): String {
        if (Process.myUid() / 100000 != 0) throw IOException("The global system theme belongs to Android user 0")
        val apk = context.applicationInfo.sourceDir
        require(apk.startsWith("/data/app/") && apk.endsWith("/base.apk") && File(apk).canonicalPath == apk)
        val quotedApk = "'" + apk.replace("'", "'\\''") + "'"
        val command = """echo $ENTERED
            [ "$(am get-current-user)" = "0" ] || exit 68
            exec env CLASSPATH=$quotedApk /system/bin/app_process /system/bin com.jerrey.monoicon.theme.archive.RootThemeArchive $verb
        """.trimIndent()
        val output = File.createTempFile("theme-result-", ".txt", context.cacheDir)
        try {
            var run = invokeRoot(listOf("su", "-M", "-c", command), patch, output)
            if (output.length() <= 4096 && ThemeRootMount.unsupportedOption(run.first, run.second, ENTERED)) {
                run = invokeRoot(listOf("su", "-c", ThemeRootMount.guard + "\n" + command), patch, output)
            }
            val (code, lines) = run
            lines.lineSequence().filter { it.startsWith(WARNING) }.forEach {
                logw("MonoIcon.Theme", it.removePrefix(WARNING))
            }
            val reply = lines.lineSequence().lastOrNull { it.startsWith(MARKER) }?.removePrefix(MARKER)
            if (reply == "EXTERNAL") throw ThemeArchiveConflictException()
            if (code != 0 || reply == null || reply.startsWith("ERROR ")) {
                val detail = reply?.removePrefix("ERROR ") ?: lines.trim().takeLast(240)
                throw IOException("Root theme operation failed (exit $code) $detail".trimEnd())
            }
            inspect?.invoke(output)
            return reply
        } finally {
            output.delete()
        }
    }

    private fun invokeRoot(args: List<String>, patch: File?, output: File): Pair<Int, String> {
        val builder = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output)
        patch?.let(builder::redirectInput)
        val child = builder.start()
        try {
            if (patch == null) child.outputStream.close()
            if (!child.waitFor(180, TimeUnit.SECONDS)) throw IOException("Root theme operation timed out")
            val lines = RandomAccessFile(output, "r").use { log ->
                val length = minOf(4096L, log.length()).toInt()
                log.seek(log.length() - length)
                ByteArray(length).also(log::readFully).decodeToString()
            }
            return child.exitValue() to lines
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }

    private const val ENTERED = "MONOICON_THEME_ROOT_ENTERED"
    internal const val MARKER = "MONOICON_THEME_V1 "
    internal const val WARNING = "MONOICON_THEME_WARNING "
    internal const val ASSET = "MONOICON_LARGE_ASSET "
}
