package com.jerrey.monoicon.hook

import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val initialized = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val iconRevision = AtomicLong()
    private val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "MonoIcon-Hos4Publish").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var publicationRequested = false
    private var lastPublished: String? = null // accessed only on worker
    private var lastIconRevision = -1L
    private var lastSaved: String? = null // includes commits awaiting launcher restart
    private var lastSavedRevision = -1L
    private var preparedLargeSignature = HyperOs4LargeIconInputs.EMPTY.signature
    private var geometryRetryKey: String? = null
    private var geometryRetryCount = 0
    private var geometryRetryPending: Long? = null
    private val mutableStatus = MutableStateFlow("Profile configuration has not been published")
    val status = mutableStatus.asStateFlow()

    fun profileChanged(context: Context) {
        ProfileRepository.invalidate()
        invalidateExport()
        publish(context)
    }

    fun invalidateExport() {
        CacheManager.clearAll()
        PixelMonetColorEngine.invalidate()
        iconRevision.incrementAndGet()
    }

    /** The restart button must wait until the new PNGs and config are committed. */
    fun flushBeforeRestart(context: Context): Boolean {
        if (ProfileRepository.nativeLauncher(context) == null) return true
        publish(context)
        return try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                ready = worker.schedule(Callable {
                    val resolution = ProfileRepository.resolve(context)
                    val colors = PixelMonetColorEngine.getIconColors()
                    val config = HyperOs4Config(ConfigManager.isEnabledFromUi() && resolution.profile != null, colors.background,
                        colors.foreground, ConfigManager.isCircleIconsEnabledFromUi(), ConfigManager.isLawniconsEnabledFromUi())
                    val awaitingGeometry = config.enabled && resolution.profile?.icons?.geometryBridge != null &&
                        preparedLargeSignature == HyperOs4LargeIconInputs.EMPTY.signature && lastPublished == lastSaved &&
                        (geometryRetryCount < 3 || geometryRetryPending != null)
                    lastSavedRevision == iconRevision.get() && lastSaved == publicationKey(config, resolution.profile, preparedLargeSignature) && !awaitingGeometry
                }, 250, TimeUnit.MILLISECONDS).get(30, TimeUnit.SECONDS)
            }
            ready
        } catch (failure: Exception) {
            logw(TAG, "configuration was not ready for restart: ${failure.message}")
            false
        }
    }

    private val configListener: (String?) -> Unit = { key ->
        if (publicationRequested && (key == null || key in setOf("enabled", "theme_id", "variant_id", "circle_icons", "lawnicons_enabled"))) {
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
            if (!publicationRequested) return
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
        // MainActivity.onResume publishes UI changes. A background launcher
        // startup job first reuses the immutable generation already on disk.
    }

    private fun invalidateAndPublish() {
        if (!publicationRequested) return
        PixelMonetColorEngine.invalidate()
        appContext?.let(::publish)
    }

    /** Coalesce Activity, binder and settings callbacks; root writes never overlap. */
    fun publish(context: Context) {
        publicationRequested = true
        val app = context.applicationContext ?: context
        val requested = generation.incrementAndGet()
        worker.schedule({
            if (generation.get() != requested) return@schedule
            try {
                val launcher = ProfileRepository.nativeLauncher(app) ?: return@schedule
                val resolution = ProfileRepository.resolve(app)
                val profile = resolution.profile
                if (profile == null) mutableStatus.value = resolution.reason
                PixelMonetColorEngine.init(app)
                val palette = PixelMonetColorEngine.getIconColors()
                val config = HyperOs4Config(
                    enabled = ConfigManager.isEnabledFromUi() && profile != null,
                    background = palette.background,
                    foreground = palette.foreground,
                    circle = ConfigManager.isCircleIconsEnabledFromUi(),
                    lawnicons = ConfigManager.isLawniconsEnabledFromUi(),
                )
                val largeIcons = if (config.enabled) HyperOs4LargeIconInputs.read(app, profile, launcher.uid)
                    else HyperOs4LargeIconInputs.EMPTY
                geometryRetryPending = null
                preparedLargeSignature = largeIcons.signature
                val content = publicationKey(config, profile, largeIcons.signature)
                val revision = iconRevision.get()
                if (generation.get() != requested) return@schedule
                if (content == lastPublished && revision == lastIconRevision) {
                    retryGeometry(app, requested, content, config.enabled && profile?.icons?.geometryBridge != null && largeIcons.geometry == null)
                    return@schedule
                }
                val icons = if (config.enabled) File(app.cacheDir, "hyperos4-export-$requested") else null
                try {
                    if (icons != null) {
                        val exported = HyperOs4IconExporter.export(app, icons, checkNotNull(profile), largeIcons) { generation.get() == requested }
                            ?: return@schedule
                        check(exported.components > 0 && exported.files > 0) { "No usable icons were exported; retaining the previous generation" }
                        logi(TAG, "rendered ${exported.components} components (${exported.files} PNG aliases) with MonoIcon")
                    }
                    if (generation.get() != requested || !publishFile(app, launcher, config, icons, revision, profile, content)) return@schedule
                } finally {
                    icons?.deleteRecursively()
                }
                lastPublished = content
                lastIconRevision = revision
                retryGeometry(app, requested, content, config.enabled && profile?.icons?.geometryBridge != null && largeIcons.geometry == null)
                // The independent refresh owner also handles late native ACKs
                // and the new PID after a launcher restart.
                logi(TAG, "native configuration published: enabled=${config.enabled} circle=${config.circle} lawnicons=${config.lawnicons}")
            } catch (t: Throwable) {
                // ScheduledThreadPoolExecutor otherwise silently consumes Errors.
                mutableStatus.value = "Publish failed: ${t.message}"
                logw(TAG, "native configuration publish failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }, 200, TimeUnit.MILLISECONDS)
    }

    private fun retryGeometry(app: Context, requested: Long, content: String, unavailable: Boolean) {
        if (!unavailable) { geometryRetryKey = null; geometryRetryCount = 0; geometryRetryPending = null; return }
        if (geometryRetryKey != content) { geometryRetryKey = content; geometryRetryCount = 0 }
        if (geometryRetryCount >= 3) return
        geometryRetryCount++
        geometryRetryPending = requested
        // First publication starts the verified native worker. Keep one bounded
        // retry chain, tied to this exact request and acknowledged publication.
        worker.schedule({
            if (generation.get() == requested && lastPublished == content) publish(app)
            else if (geometryRetryPending == requested) geometryRetryPending = null
        }, 1, TimeUnit.SECONDS)
    }

    private fun publicationKey(config: HyperOs4Config, profile: CompatibilityProfile?, largeSignature: String): String =
        config.encode() + (profile?.digest ?: ProfileRepository.REVOKED_DIGEST) + largeSignature

    private fun publishFile(app: Context, launcher: ApplicationInfo, config: HyperOs4Config, icons: File?, revision: Long,
                            profile: CompatibilityProfile?, publication: String): Boolean {
        // dataDir may be /data/data, an app-isolated tmpfs in our mount namespace.
        // An su child inherits it: mkdir there can succeed in an invisible directory.
        // /data/user can be overmounted too; su -M below uses the global namespace.
        val directory = File(HyperOs4Storage.directory(launcher.uid))
        val destination = File(directory, "monoicon_palette.txt")
        val staging = File(directory, "monoicon_palette.txt.new")
        val output = File(app.cacheDir, "hyperos4-publish.log")
        val iconRoot = File(directory, "monoicon_icons")
        val iconGeneration = "g-" + UUID.randomUUID().toString().replace("-", "")
        val largeIconsPublished = icons?.let { File(it, ".large-icons").isFile } == true
        val refreshStatus: (String) -> Unit = { message ->
            mutableStatus.value = if (largeIconsPublished)
                "$message\nRestart the scope to refresh cached large icons." else message
        }
        val digest = profile?.digest ?: ProfileRepository.REVOKED_DIGEST
        val content = config.encode(iconGeneration, digest)
        val profileSource = File(app.cacheDir, "hyperos4-profile-publish.json")
        profile?.let { profileSource.writeBytes(it.source) }
        val profileRoot = File(directory, "monoicon_profiles")
        val profileTarget = File(profileRoot, "$digest.json")
        val profileStage = File(profileRoot, "$digest.json.new")
        val active = File(directory, "monoicon_profile.active")
        val activeStage = File(directory, "monoicon_profile.active.new")
        val switchProfile = if (profile == null) "" else """
            phase=profile
            mkdir -p ${quote(profileRoot.path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(profileRoot.path)}
            chmod 700 ${quote(profileRoot.path)}
            cp ${quote(profileSource.path)} ${quote(profileStage.path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(profileStage.path)}
            chmod 600 ${quote(profileStage.path)}
            restorecon -R ${quote(profileRoot.path)}
            mv -f ${quote(profileStage.path)} ${quote(profileTarget.path)}
            cmp -s ${quote(profileSource.path)} ${quote(profileTarget.path)}
            printf '%s' ${quote(digest)} > ${quote(activeStage.path)}
            chown ${launcher.uid}:${launcher.uid} ${quote(activeStage.path)}
            chmod 600 ${quote(activeStage.path)}
            restorecon ${quote(activeStage.path)}
            mv -f ${quote(activeStage.path)} ${quote(active.path)}
        """.trimIndent()
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
            chmod 700 ${quote(directory.path)}
            restorecon ${quote(directory.path)}
            previousGeneration=
            if [ -f ${quote(destination.path)} ]; then
                read -r oldMagic oldVersion oldEnabled oldBackground oldForeground oldCircle oldLawnicons previousGeneration previousProfile < ${quote(destination.path)} || true
            fi
            $switchProfile
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
            cat ${quote(File(directory, "monoicon_hyos_palette.txt").path)} 2>/dev/null || true
            exit 10
        """.trimIndent()
        mutableStatus.value = "Publishing ${profile?.name ?: "disabled configuration"}"
        val process = ProcessBuilder("su", "-M", "-c", script)
            .redirectErrorStream(true).redirectOutput(output).start()
        return try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                mutableStatus.value = "Publish timed out; root access is required"
                logw(TAG, "native configuration publish timed out; root access is required on HyperOS 4")
                false
            } else if (process.exitValue() == 10) {
                lastSaved = publication
                lastSavedRevision = revision
                val native = runCatching { output.readText().takeLast(1200) }.getOrDefault("")
                mutableStatus.value = "Saved; restart launcher to apply Profile. Native acknowledgement pending.\n$native"
                logw(TAG, mutableStatus.value)
                HyperOs4IconRefresh.schedule(app, launcher, profile, content, config.enabled, refreshStatus)
                false
            } else if (process.exitValue() != 0) {
                val reason = runCatching { output.readLines().takeLast(3).joinToString("; ").take(600) }.getOrDefault("")
                mutableStatus.value = "Publish failed (root exit=${process.exitValue()}): $reason"
                logw(TAG, "native configuration was not published (root exit=${process.exitValue()}): $reason")
                false
            } else {
                lastSaved = publication
                lastSavedRevision = revision
                refreshStatus("Applied: ${profile?.name ?: "disabled"} · ${digest.take(12)}")
                HyperOs4IconRefresh.schedule(app, launcher, profile, content, config.enabled, refreshStatus)
                true
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
            profileSource.delete()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
