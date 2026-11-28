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
import android.os.Handler
import android.os.Looper
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.safety.LauncherSafety
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/** SystemUI remains available after the module settings process exits. */
internal object HyperOs4SystemEvents {
    private val registered = AtomicBoolean()
    private const val MODULE = "com.jerrey.monoicon"

    fun install(api: XposedInterface) {
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        HookRegistry.intercept(api, attach) { chain ->
            val result = chain.proceed()
            val context = chain.getArg(0) as? Context
            if (context?.packageName == "com.android.systemui") {
                Handler(Looper.getMainLooper()).post {
                    runCatching { register(context) }.onFailure {
                        logw("MonoIcon.HyperOS4", "System event registration failed: ${it.message}")
                    }
                }
            }
            result
        }
    }

    fun wake(context: Context, reason: String, pkg: String? = null) {
        context.sendBroadcast(Intent(reason)
            .setComponent(ComponentName(MODULE, HyperOs4RefreshReceiver::class.java.name))
            .putExtra("package", pkg)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
    }

    fun register(context: Context) {
        if (Application.getProcessName() != "com.android.systemui" || !registered.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        if (!HyperOs4Environment.isVersion4()) {
            LauncherSafety.launcherUid(app)?.let { LauncherSafetyMonitor.start(app, it) }
            return
        }
        if (!HyperOs4Environment.isSupported(app)) return

        val handler = Handler(Looper.getMainLooper())
        val onWallpaper = Runnable { wake(app, HyperOs4ThemeSync.WALLPAPER) }
        observe("wallpaper") {
            WallpaperManager.getInstance(app).addOnColorsChangedListener({ _, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    handler.removeCallbacks(onWallpaper)
                    handler.postDelayed(onWallpaper, 750)
                }
            }, handler)
        }
        observe("configuration") {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val reason = if (intent.action == Intent.ACTION_CONFIGURATION_CHANGED) HyperOs4ThemeSync.STYLE else HyperOs4ThemeSync.SCAN
                    wake(context, reason)
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(Intent.ACTION_USER_UNLOCKED)
            }, Context.RECEIVER_NOT_EXPORTED)
        }
        observe("packages") {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    intent.data?.schemeSpecificPart?.let { wake(context, HyperOs4ThemeSync.PACKAGE, it) }
                }
            }, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }, Context.RECEIVER_NOT_EXPORTED)
        }
        wake(app, HyperOs4ThemeSync.SCAN)
    }

    private inline fun observe(name: String, register: () -> Unit) {
        runCatching(register).onFailure { logw("MonoIcon.Theme", "Could not observe $name: ${it.message}") }
    }
}

/** The manifest restricts delivery to privileged system senders. */
class HyperOs4RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != LauncherSafetyMonitor.ACTION_RESTARTED) {
            val reason = intent.action ?: HyperOs4ThemeSync.SCAN
            if (reason in setOf(HyperOs4ThemeSync.SCAN, HyperOs4ThemeSync.STYLE, HyperOs4ThemeSync.WALLPAPER,
                    HyperOs4ThemeSync.CHECK, HyperOs4ThemeSync.VALIDATION, HyperOs4ThemeSync.PACKAGE, HyperOs4ThemeSync.LARGE)) {
                HyperOs4ThemeSync.enqueue(context, reason, intent.getStringExtra("package"))
            }
            return
        }
        if (HyperOs4Environment.isVersion4()) return
        val extras = android.os.PersistableBundle().apply { putInt("launcher_pid", intent.getIntExtra("launcher_pid", 0)) }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(0x4d494350, ComponentName(context, LauncherSafetyJob::class.java))
            .setExtras(extras).setOverrideDeadline(0).build())
    }
}

/** Reads the startup guard of the Java launcher after a restart. */
class LauncherSafetyJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (HyperOs4Environment.isVersion4()) return false
        Thread({
            try {
                LauncherSafety.synchronizeStartup(applicationContext, params.extras.getInt("launcher_pid"))
                jobFinished(params, false)
            } catch (e: Exception) {
                logw("MonoIcon.Launcher", "Startup check failed: ${e.message}")
                jobFinished(params, true)
            }
        }, "MonoIcon-LauncherStartup").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
