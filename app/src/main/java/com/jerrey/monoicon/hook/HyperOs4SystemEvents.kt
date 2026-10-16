package com.jerrey.monoicon.hook

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
import com.jerrey.monoicon.cache.CacheManager
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logw
import java.util.concurrent.atomic.AtomicBoolean

/** The persistent SystemUI process wakes the exporter after the settings UI exits. */
internal object HyperOs4SystemEvents {
    private val registered = AtomicBoolean()
    private const val MODULE = "com.jerrey.monoicon"

    fun register(context: Context) {
        if (registered.get()) return
        val info = runCatching { context.packageManager.getPackageInfo("com.miui.home", PackageManager.PackageInfoFlags.of(0)) }.getOrNull() ?: return
        if (!HyperOs4Config.supports(info.longVersionCode, info.versionName,
                info.applicationInfo!!.flags and ApplicationInfo.FLAG_HAS_CODE != 0,
                "arm64-v8a" in Build.SUPPORTED_ABIS)) return
        if (!registered.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
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
        val urgent = JobInfo.Builder(JOB_ID, component).setExpedited(true).build()
        if (scheduler.schedule(urgent) != JobScheduler.RESULT_SUCCESS) {
            scheduler.schedule(JobInfo.Builder(JOB_ID, component).setOverrideDeadline(0).build())
        }
    }

    companion object { const val JOB_ID = 0x4d49434f }
}

/** JobScheduler keeps the app runnable while rendering in the background. */
class HyperOs4RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            HyperOs4PalettePublisher.invalidateExport()
            val saved = HyperOs4PalettePublisher.flushBeforeRestart(applicationContext)
            jobFinished(params, !saved)
        }, "MonoIcon-Hos4Job").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
