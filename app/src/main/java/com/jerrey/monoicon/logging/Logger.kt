package com.jerrey.monoicon.logging

import android.util.Log

/**
 * Unified logging interface used by all MonoIcon components.
 *
 * Implementation must never throw — a failed log statement
 * must silently fall through rather than crash the caller.
 */
interface Logger {

    /** Whether debug-level logging is currently active. */
    val isDebugEnabled: Boolean

    /** Log a debug message. Not printed in release builds. */
    fun d(tag: String, message: String)

    /** Log an informational message. */
    fun i(tag: String, message: String)

    /** Log a warning, optionally with an associated throwable. */
    fun w(tag: String, message: String, throwable: Throwable? = null)

    /** Log an error, optionally with an associated throwable. */
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/**
 * Default [Logger] implementation.
 *
 * Every record is forwarded to [LogStore] so it reaches the in-app log page,
 * and also written to logcat, which stays useful while debugging through adb.
 * Logcat is a debugging convenience here, not the storage: the in-app page
 * reads [LogStore] and never logcat, so it needs no permission.
 *
 * Debug logging is controlled by the [isDebugEnabled] flag rather than
 * BuildConfig, because the logging package must not depend on the
 * application module's generated BuildConfig class.
 *
 * Call [LogcatLogger.setDebugEnabled] from the application entry point
 * (e.g., MainActivity or hook initialization) to configure this at startup.
 */
object LogcatLogger : Logger {

    @Volatile
    private var debugEnabled: Boolean = false

    /** Configure whether debug-level messages are printed. */
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
    }

    override val isDebugEnabled: Boolean
        get() = debugEnabled

    override fun d(tag: String, message: String) {
        // Two separate conditions, deliberately:
        //  - Logcat output follows the debug switch, exactly as before.
        //  - Storing the entry additionally requires a process that opened the
        //    store. In the launcher these are the per-icon diagnostics (cache
        //    hits, mask tiers) emitted thousands of times per scroll; routing
        //    them through the store's lock would tax the very path they exist
        //    to measure, and they could never be shown in the settings UI
        //    anyway.
        if (!isDebugEnabled) return
        Log.d(tag, message)
        if (LogStore.isInstalled) LogStore.record(LogStore.LEVEL_DEBUG, tag, message)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, message)
        if (LogStore.isInstalled) LogStore.record(LogStore.LEVEL_INFO, tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        if (LogStore.isInstalled) LogStore.record(LogStore.LEVEL_WARN, tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        if (LogStore.isInstalled) LogStore.record(LogStore.LEVEL_ERROR, tag, message, throwable)
    }
}

// ── Top-level convenience functions ────────────────────────────────

/** Log a debug message using the default [LogcatLogger]. */
fun logd(tag: String, message: String) = LogcatLogger.d(tag, message)

/** Build hot-path debug messages only when logging is enabled. */
inline fun logd(tag: String, message: () -> String) {
    if (LogcatLogger.isDebugEnabled) LogcatLogger.d(tag, message())
}

/** Log an informational message using the default [LogcatLogger]. */
fun logi(tag: String, message: String) = LogcatLogger.i(tag, message)

/** Log a warning using the default [LogcatLogger]. */
fun logw(tag: String, message: String, t: Throwable? = null) = LogcatLogger.w(tag, message, t)

/** Log an error using the default [LogcatLogger]. */
fun loge(tag: String, message: String, t: Throwable? = null) = LogcatLogger.e(tag, message, t)
