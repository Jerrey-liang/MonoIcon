package com.jerrey.monoicon.hook

import android.app.Application
import android.app.WallpaperManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.jerrey.monoicon.cache.CacheManager
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.profile.ProfileRepository
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** The persistent SystemUI process wakes the exporter after the settings UI exits. */
internal object HyperOs4SystemEvents {
    private val registered = AtomicBoolean()
    private const val MODULE = "com.jerrey.monoicon"

    /** Register from process startup, independently of notification icon rendering. */
    fun install(api: XposedInterface) {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        api.hook(attach)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val context = chain.getArg(0) as? Context
                if (context?.packageName == "com.android.systemui") {
                    Handler(Looper.getMainLooper()).post {
                        runCatching { register(context) }.onFailure {
                            logw("MonoIcon.HyperOS4", "startup observer registration failed: ${it.message}")
                        }
                    }
                }
                result
            }
    }

    fun register(context: Context) {
        if (Application.getProcessName() != "com.android.systemui") return
        if (registered.get()) return
        val info = runCatching { context.packageManager.getPackageInfo("com.miui.home", PackageManager.PackageInfoFlags.of(0)) }.getOrNull() ?: return
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        // Both launcher generations need the protected safety-status wakeup.
        info.applicationInfo?.let { HyperOs4LauncherMonitor.start(app, it.uid) }
        // SystemUI only detects the native generation. The app owns profile
        // selection and ELF inspection after the protected wakeup broadcast.
        if (info.applicationInfo?.flags?.and(ApplicationInfo.FLAG_HAS_CODE) != 0 ||
            "arm64-v8a" !in Build.SUPPORTED_ABIS) return
        val handler = Handler(Looper.getMainLooper())
        val refresh = Runnable {
            if (ConfigManager.isEnabled()) runCatching {
                app.sendBroadcast(Intent().setComponent(ComponentName(MODULE, HyperOs4RefreshReceiver::class.java.name))
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
            }.onFailure { logw("MonoIcon.HyperOS4", "export wakeup failed: ${it.message}") }
        }
        fun changed() {
            CacheManager.clearAll()
            handler.removeCallbacks(refresh)
            handler.postDelayed(refresh, 300)
        }
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener({ _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) changed()
            }, handler)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = changed()
            }
            app.registerReceiver(receiver, IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            app.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }, Context.RECEIVER_NOT_EXPORTED)
        }.onFailure { logw("MonoIcon.HyperOS4", "system event observers unavailable: ${it.message}") }
    }
}

/** Manifest permission restricts wakeups to the privileged system interface. */
class HyperOs4RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, HyperOs4RefreshJob::class.java)
        val restart = intent.action == HyperOs4LauncherMonitor.ACTION_RESTARTED
        // A startup delivery must not replace a pending wallpaper/package export.
        val jobId = if (restart) JOB_ID + 1 else JOB_ID
        val extras = PersistableBundle().apply {
            putBoolean("launcher_restart", restart)
            putInt("launcher_pid", if (restart) intent.getIntExtra("launcher_pid", 0) else 0)
        }
        val urgent = JobInfo.Builder(jobId, component).setExtras(extras).setExpedited(true).build()
        if (scheduler.schedule(urgent) != JobScheduler.RESULT_SUCCESS) {
            scheduler.schedule(JobInfo.Builder(jobId, component).setExtras(extras).setOverrideDeadline(0).build())
        }
    }

    companion object { const val JOB_ID = 0x4d49434f }
}

/** JobScheduler keeps the app runnable while rendering in the background. */
class HyperOs4RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val completed = runCatching {
                if (com.jerrey.monoicon.safety.LauncherSafety.synchronizeStartup(applicationContext, params.extras.getInt("launcher_pid"))) {
                    // Persist the disabled native commit too; the startup latch
                    // already prevents hooks even if publication is unavailable.
                    HyperOs4PalettePublisher.flushBeforeRestart(applicationContext)
                    return@runCatching true
                }
                if (ProfileRepository.nativeLauncher(applicationContext) == null) return@runCatching true
                if (params.extras.getBoolean("launcher_restart")) {
                    // A transient boot-time metadata/binder failure must not
                    // replace a valid published Profile with a revoked commit.
                    if (ProfileRepository.resolve(applicationContext).profile == null) return@runCatching false
                    HyperOs4IconRefresh.restoreAfterLauncherRestart(applicationContext)?.let { restored ->
                        if (!restored.completion.get(75, TimeUnit.SECONDS)) return@runCatching false
                        if (!HyperOs4IconRefresh.needsPublicationAfterRestore(applicationContext, restored)) {
                            return@runCatching true
                        }
                    }
                } else HyperOs4PalettePublisher.invalidateExport()
                val saved = HyperOs4PalettePublisher.flushBeforeRestart(applicationContext)
                if (saved) {
                    // Publishing can be a no-op for an unchanged generation.
                    // Redeliver to the new PID anyway and keep the app runnable
                    // until the root worker finishes, even with no Activity open.
                    HyperOs4IconRefresh.afterLauncherRestart(applicationContext)?.get(75, TimeUnit.SECONDS) ?: true
                } else false
            }.getOrElse {
                logw("MonoIcon.HyperOS4", "background icon refresh failed: ${it.message}")
                false
            }
            jobFinished(params, !completed)
        }, "MonoIcon-Hos4Job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
