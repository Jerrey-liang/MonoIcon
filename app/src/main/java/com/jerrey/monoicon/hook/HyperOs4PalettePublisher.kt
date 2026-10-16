package com.jerrey.monoicon.hook

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.cache.CacheManager
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** App-side configuration transport for the native-only launcher. */
internal object HyperOs4PalettePublisher {
    private const val TAG = "MonoIcon.HyperOS4"
    private const val PACKAGE = "com.miui.home"
    private const val SETTINGS_KEY = "key_monochrome_color"
    private val initialized = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val iconRevision = AtomicLong()
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "MonoIcon-Hos4Publish").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    private var lastPublished: String? = null // accessed only on worker
    private var lastIconRevision = -1L
    private var lastSaved: String? = null // includes commits awaiting launcher restart
    private var lastSavedRevision = -1L

    fun invalidateExport() {
        CacheManager.clearAll()
        PixelMonetColorEngine.invalidate()
        iconRevision.incrementAndGet()
    }

    /** The restart button must wait until the new PNGs and config are committed. */
    fun flushBeforeRestart(context: Context): Boolean {
        if (supportedLauncher(context) == null) return true
        publish(context)
        return try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                ready = worker.schedule(Callable {
                    val colors = PixelMonetColorEngine.getIconColors()
                    lastSavedRevision == iconRevision.get() && lastSaved == HyperOs4Config(ConfigManager.isEnabledFromUi(), colors.background,
                        colors.foreground, ConfigManager.isCircleIconsEnabledFromUi(),
                        ConfigManager.isLawniconsEnabledFromUi()).encode()
                }, 250, TimeUnit.MILLISECONDS).get(30, TimeUnit.SECONDS)
            }
            ready
        } catch (failure: Exception) {
            logw(TAG, "configuration was not ready for restart: ${failure.message}")
            false
        }
    }

    private val configListener: (String?) -> Unit = { key ->
        if (key == null || key in setOf("enabled", "theme_id", "variant_id", "circle_icons", "lawnicons_enabled")) {
            appContext?.let(::publish)
        }
    }
    private val wallpaperListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) invalidateAndPublish()
    }
    private val configurationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = invalidateAndPublish()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.data?.schemeSpecificPart?.let(CacheManager::invalidatePackage)
            iconRevision.incrementAndGet()
            publish(context)
        }
    }

    /** Register once per app process, before the Activity or preference callbacks. */
    fun init(context: Context) {
        val app = context.applicationContext ?: context
        if (!initialized.compareAndSet(false, true)) return
        appContext = app
        ConfigManager.addOnConfigChangedListener(configListener)
        if (supportedLauncher(app) == null) return
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener(
                wallpaperListener, Handler(Looper.getMainLooper()),
            )
            app.registerReceiver(
                configurationReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                    addAction(Intent.ACTION_WALLPAPER_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            app.registerReceiver(packageReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { logw(TAG, "palette observers unavailable: ${it.message}") }
        publish(app)
    }

    private fun invalidateAndPublish() {
        PixelMonetColorEngine.invalidate()
        appContext?.let(::publish)
    }

    /** Coalesce Activity, binder and settings callbacks; root writes never overlap. */
    fun publish(context: Context) {
        val app = context.applicationContext ?: context
        val requested = generation.incrementAndGet()
        worker.schedule({
            if (generation.get() != requested) return@schedule
            try {
                val launcher = supportedLauncher(app) ?: return@schedule
                PixelMonetColorEngine.init(app)
                val palette = PixelMonetColorEngine.getIconColors()
                val config = HyperOs4Config(
                    enabled = ConfigManager.isEnabledFromUi(),
                    background = palette.background,
                    foreground = palette.foreground,
                    circle = ConfigManager.isCircleIconsEnabledFromUi(),
                    lawnicons = ConfigManager.isLawniconsEnabledFromUi(),
                )
                val content = config.encode()
                val revision = iconRevision.get()
                if (generation.get() != requested || (content == lastPublished && revision == lastIconRevision)) return@schedule
                val icons = if (config.enabled) File(app.cacheDir, "hyperos4-export-$requested") else null
                try {
                    if (icons != null) {
                        val exported = HyperOs4IconExporter.export(app, icons) { generation.get() == requested }
                            ?: return@schedule
                        logi(TAG, "rendered ${exported.components} components (${exported.files} PNG aliases) with MonoIcon")
                    }
                    if (generation.get() != requested || !publishFile(app, launcher, config, icons, revision)) return@schedule
                } finally {
                    icons?.deleteRecursively()
                }
                lastPublished = content
                lastIconRevision = revision
                // A refresh hint is never evidence that rendering succeeded.
                runCatching {
                    app.contentResolver.notifyChange(Settings.System.getUriFor(SETTINGS_KEY), null, 0)
                }.onFailure { logw(TAG, "launcher refresh hint failed: ${it.message}") }
                logi(TAG, "native configuration published: enabled=${config.enabled} circle=${config.circle} lawnicons=${config.lawnicons}")
            } catch (t: Throwable) {
                // ScheduledThreadPoolExecutor otherwise silently consumes Errors.
                logw(TAG, "native configuration publish failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, 200, TimeUnit.MILLISECONDS)
    }

    private fun supportedLauncher(app: Context): ApplicationInfo? = try {
        val info = app.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
        info.applicationInfo?.takeIf {
            HyperOs4Config.supports(
                info.longVersionCode, info.versionName,
                it.flags and ApplicationInfo.FLAG_HAS_CODE != 0,
                "arm64-v8a" in Build.SUPPORTED_ABIS,
            ) && it.splitSourceDirs.isNullOrEmpty()
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun publishFile(app: Context, launcher: ApplicationInfo, config: HyperOs4Config, icons: File?, revision: Long): Boolean {
        // dataDir may be /data/data, an app-isolated tmpfs in our mount namespace.
        // An su child inherits it: mkdir there can succeed in an invisible directory.
        // /data/user can be overmounted too; su -M below uses the global namespace.
        val userId = launcher.uid / 100_000
        val directory = File("/data/user/$userId/$PACKAGE/files")
        val destination = File(directory, "monoicon_palette.txt")
        val staging = File(directory, "monoicon_palette.txt.new")
        val output = File(app.cacheDir, "hyperos4-publish.log")
        val iconRoot = File(directory, "monoicon_icons")
        val iconGeneration = "g-" + UUID.randomUUID().toString().replace("-", "")
        val content = config.encode(iconGeneration)
        val iconTarget = File(iconRoot, iconGeneration)
        val switchIcons = if (icons == null) "" else """
            phase=icons
            mkdir -p ${quote(iconRoot.path)}
            test ! -e ${quote(iconTarget.path)}
            cp -R ${quote(icons.path)} ${quote(iconTarget.path)}
            printf '%s' ${quote(iconGeneration)} > ${quote(File(iconTarget, ".ready").path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(iconRoot.path)}
            chown -R ${launcher.uid}:${launcher.uid} ${quote(iconTarget.path)}
            chmod 700 ${quote(iconRoot.path)}
            chmod -R u=rwX,go= ${quote(iconTarget.path)}
            restorecon -R ${quote(iconRoot.path)}
        """.trimIndent()
        // Only the configuration rename commits the immutable PNG generation.
        // No symlink-to-directory mv: Android toybox can reject that replacement.
        val script = """
            set -e
            phase=directory
            trap 'result=${'$'}?; if [ "${'$'}result" -ne 0 ]; then echo "publish failed: phase=${'$'}phase exit=${'$'}result"; fi' EXIT
            mkdir -p ${quote(directory.path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(directory.path)}
            previousGeneration=
            if [ -f ${quote(destination.path)} ]; then
                read -r oldMagic oldVersion oldEnabled oldBackground oldForeground oldCircle oldLawnicons previousGeneration < ${quote(destination.path)} || true
            fi
            $switchIcons
            phase=configuration
            printf '%s' ${quote(content)} > ${quote(staging.path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(staging.path)}
            chmod 600 ${quote(staging.path)}
            restorecon ${quote(staging.path)}
            mv -f ${quote(staging.path)} ${quote(destination.path)}
            test -s ${quote(destination.path)}
            phase=acknowledgement
            attempts=0
            while [ "${'$'}attempts" -lt 20 ]; do
                if cmp -s ${quote(destination.path)} ${quote(File(directory, "monoicon_palette.applied").path)}; then
                    # Keep the active and previous generations for in-flight readers.
                    # Remove only directories with the exact names we generate.
                    for old in ${quote(iconRoot.path)}/g-*; do
                        [ -d "${'$'}old" ] && [ ! -L "${'$'}old" ] || continue
                        oldName=${'$'}{old##*/}
                        [ "${'$'}{#oldName}" -eq 34 ] || continue
                        case "${'$'}{oldName#g-}" in *[!0-9a-f]*) continue ;; esac
                        [ "${'$'}oldName" = ${quote(iconGeneration)} ] && continue
                        [ "${'$'}oldName" = "${'$'}previousGeneration" ] && continue
                        rm -rf "${'$'}old" || true
                    done
                    exit 0
                fi
                attempts=${'$'}((attempts + 1))
                sleep 0.2
            done
            exit 10
        """.trimIndent()
        val process = ProcessBuilder("su", "-M", "-c", script)
            .redirectErrorStream(true).redirectOutput(output).start()
        return try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                logw(TAG, "native configuration publish timed out; root access is required on HyperOS 4")
                false
            } else if (process.exitValue() == 10) {
                lastSaved = config.encode()
                lastSavedRevision = revision
                logw(TAG, "configuration and icons saved; native acknowledgement pending (restart the launcher after updating the module)")
                false
            } else if (process.exitValue() != 0) {
                val reason = runCatching { output.readLines().takeLast(3).joinToString("; ").take(600) }.getOrDefault("")
                logw(TAG, "native configuration was not published (root exit=${process.exitValue()}): $reason")
                false
            } else {
                lastSaved = config.encode()
                lastSavedRevision = revision
                true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
