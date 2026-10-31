package com.jerrey.monoicon.hook

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logw
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** SystemUI survives launcher restarts, including restarts outside our settings UI. */
internal object HyperOs4LauncherMonitor {
    const val ACTION_RESTARTED = "com.jerrey.monoicon.action.HYPEROS4_LAUNCHER_RESTARTED"

    fun start(context: Context, launcherUid: Int) {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        // Keep Binder queries off SystemUI's main thread. This is installed only
        // for the native launcher, once by HyperOs4SystemEvents.register().
        val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "MonoIcon-Hos4Launcher").apply { isDaemon = true }
        }
        var deliveredPid = 0
        var reportedFailure = false
        worker.scheduleWithFixedDelay({
            try {
                if (!ConfigManager.isEnabled()) {
                    deliveredPid = 0
                    return@scheduleWithFixedDelay
                }
                val processes = activityManager.runningAppProcesses ?: return@scheduleWithFixedDelay
                val pid = processes.firstOrNull {
                    it.uid == launcherUid && it.processName == "com.miui.home"
                }?.pid ?: 0
                if (pid == 0) {
                    deliveredPid = 0
                } else if (pid != deliveredPid) {
                    // The receiver is protected by STATUS_BAR_SERVICE. The job
                    // waits for this launcher's native ACK and dynamic receiver;
                    // observing a PID alone is not a signal to send icon updates.
                    context.sendBroadcast(Intent(ACTION_RESTARTED)
                        .setComponent(ComponentName("com.jerrey.monoicon", HyperOs4RefreshReceiver::class.java.name))
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
                    deliveredPid = pid
                }
                reportedFailure = false
            } catch (failure: Exception) {
                if (!reportedFailure) logw("MonoIcon.HyperOS4", "launcher observer failed: ${failure.message}")
                reportedFailure = true
            }
        }, 0, 2, TimeUnit.SECONDS)
    }
}
