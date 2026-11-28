package com.jerrey.monoicon.hook

import android.content.Context
import android.content.pm.PackageManager
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.profile.ProfileRepository
import com.jerrey.monoicon.theme.wallpaper.WallpaperSourceRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Optional MAML resources for a verified launcher. Theme archive jobs never wait for this worker. */
internal object HyperOs4LargeIconPublisher {
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "MonoIcon-LargeIcons") }
    private val revision = AtomicLong()
    private var lastPublished: String? = null // worker-owned

    fun init(context: Context) {
        if (!HyperOs4Environment.isSupported(context)) return
        ConfigManager.addOnConfigChangedListener {
            if (ConfigManager.xposedService() != null) publish(context)
        }
        WallpaperSourceRepository.addListener {
            if (ConfigManager.xposedService() != null) publish(context)
        }
    }

    fun publish(context: Context) {
        if (!HyperOs4Environment.isSupported(context)) return
        val token = revision.incrementAndGet()
        worker.schedule({ sync(context.applicationContext, token, 0) }, 350, TimeUnit.MILLISECONDS)
    }

    fun flushBeforeRestart(context: Context): Boolean {
        if (!HyperOs4Environment.isSupported(context)) return true
        val token = revision.incrementAndGet()
        return runCatching { worker.submit<Boolean> { sync(context, token, 0) }.get(25, TimeUnit.SECONDS) }
            .getOrDefault(false)
    }

    private fun sync(context: Context, token: Long, attempt: Int): Boolean {
        if (revision.get() != token) return true
        return runCatching {
            val extension = ProfileRepository.resolve(context)
            val profile = extension.profile ?: return true
            val launcher = checkNotNull(extension.launcher)
            val parent = HyperOs4Storage.directory(launcher.uid)
            val active = "$parent/monoicon_large_icons.active"
            if (!ConfigManager.isEnabledFromUi()) {
                root("rm -f '$active'")
                lastPublished = null
                return true
            }

            val inputs = HyperOs4LargeIconInputs.read(context, profile, launcher.uid)
            val stamp = if (inputs.geometry == null) null else {
                val versions = inputs.icons.map { it.packageName }.distinct().sorted().map { pkg ->
                    context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).lastUpdateTime
                }
                "${profile.digest}:${inputs.signature}:${HyperOs4ThemeRenderer.signature(context)}:$versions"
            }
            if (stamp != null && stamp == lastPublished) return true
            val generation = "g-" + UUID.randomUUID().toString().replace("-", "")
            val exported = File(context.cacheDir, "large-$generation")
            try {
                HyperOs4IconExporter.exportLargeIcons(context, exported, inputs) { token == revision.get() } ?: return true
                File(exported, ".ready").writeText(generation)
                val directory = "$parent/monoicon_icons"
                val target = "$directory/$generation"
                val stage = "$parent/.monoicon_large_$generation"
                val uid = launcher.uid
                // Keep the last measured resources while the phone's layout bridge starts.
                val retain = if (inputs.geometry == null) """
                    if [ -f '$active' ] && read -r magic version previous digest < '$active' &&
                        [ "${'$'}magic" = MONOICON_LARGE ] && [ "${'$'}version" = 1 ] &&
                        [ "${'$'}digest" = '${profile.digest}' ]; then
                        case "${'$'}previous" in g-*[!0-9a-f]*) ;; g-*)
                            if [ "${'$'}{#previous}" = 34 ] && [ -s '$directory'/"${'$'}previous"/.ready ]; then exit 0; fi ;;
                        esac
                    fi
                """.trimIndent() else ""
                if (revision.get() != token) return true
                root("""
                    set -eu
                    [ ! -L '$parent' ] && [ ! -L '$directory' ] && [ ! -L '$active' ] || exit 1
                    mkdir -p '$directory'
                    chown $uid:$uid '$directory' '$parent'
                    chmod 700 '$directory'
                    restorecon '$parent' '$directory'
                    $retain
                    test ! -e '$target' && test ! -L '$target'
                    cp -R '${exported.path}' '$target'
                    chown -R $uid:$uid '$target'
                    chmod -R u=rwX,go= '$target'
                    restorecon -R '$target'
                    test ! -e '$stage' && test ! -L '$stage'
                    printf '%s\n' 'MONOICON_LARGE 1 $generation ${profile.digest}' > '$stage'
                    chmod 600 '$stage'
                    chown $uid:$uid '$stage'
                    restorecon '$stage'
                    previous=
                    if [ -f '$active' ]; then read -r magic version previous digest < '$active' || true; fi
                    mv -f '$stage' '$active'
                    for old in '$directory'/g-*; do
                        [ -d "${'$'}old" ] && [ ! -L "${'$'}old" ] || continue
                        name=${'$'}{old##*/}
                        [ "${'$'}{#name}" = 34 ] || continue
                        case "${'$'}{name#g-}" in *[!0-9a-f]*) continue ;; esac
                        [ "${'$'}name" = '$generation' ] || [ "${'$'}name" = "${'$'}previous" ] || rm -rf "${'$'}old"
                    done
                """.trimIndent())
                lastPublished = stamp
            } finally {
                exported.deleteRecursively()
            }
            if (inputs.geometry == null && attempt < 3) {
                worker.schedule({ sync(context, token, attempt + 1) }, 2, TimeUnit.SECONDS)
            }
            true
        }.getOrElse {
            logw("MonoIcon.LargeIcons", "Optional resources unavailable: ${it.message}")
            false
        }
    }

    private fun root(script: String) {
        val process = ProcessBuilder("su", "-M", "-c", script)
            .redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        try {
            check(process.waitFor(12, TimeUnit.SECONDS) && process.exitValue() == 0) { "Root publication failed" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.outputStream.close()
        }
    }
}
