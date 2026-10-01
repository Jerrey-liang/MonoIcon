package com.jerrey.monoicon

import android.app.Application
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.LogStore
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logi

/**
 * Process entry point for the MonoIcon settings UI (Phase 14).
 *
 * Exists so logging is running before any Activity, Service or Compose code
 * touches it. Without this the first entries a user would want — configuration
 * load, the Activity coming up — would be produced before there was anywhere
 * to put them.
 *
 * The launcher and SystemUI processes never see this class: LSPosed loads the
 * module APK into them directly, without going through [Application]. Those
 * processes get the in-memory half of [LogStore] and nothing else, which is
 * all they can meaningfully use.
 */
class MonoIconApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The hook process turns this on for itself in IconThemeHook, but that
        // code never runs here — without this line the settings process would
        // drop every debug entry, and the log page would show lifecycle lines
        // only. Debug stays off in release builds, where the per-icon
        // diagnostics are not worth the locking cost.
        LogcatLogger.setDebugEnabled(BuildConfig.DEBUG)

        val fileLogReady = LogStore.install(this)
        logi(TAG, "application created (pid ${android.os.Process.myPid()})")
        logi(TAG, "file log ${if (fileLogReady) "ready" else "unavailable — memory only"}")

        // ConfigManager.init binds the SharedPreferences the launcher hook
        // process reads. Doing it here (idempotent — MainActivity still calls
        // it) means configuration problems are logged from the very start.
        runCatching { ConfigManager.init(this) }
            .onSuccess { logi(TAG, "config initialised") }
            .onFailure { loge(TAG, "config initialisation failed", it) }

        installCrashLogger()
    }

    /**
     * Records an uncaught exception before the process dies.
     *
     * The default handler runs afterwards, so the crash still behaves exactly
     * as it would without us — this only makes sure the reason is on disk and
     * waiting on the log page when the user comes back.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                loge(TAG, "uncaught exception on '${thread.name}'", throwable)
                LogStore.flushNow()
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "MonoIcon.App"
    }
}
