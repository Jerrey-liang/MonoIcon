package com.jerrey.monoicon.config

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime configuration bridge between the settings UI and the hook runtime
 * (Phase 4.1).
 *
 * ## Process model
 * The settings UI runs in the module's own process; the hooks run inside the
 * HyperOS launcher process. Both sides read/write the same SharedPreferences
 * file (`monoicon_config`) inside the module's data directory:
 *
 * - **UI process**: plain [SharedPreferences] via [init] — can write.
 * - **Launcher process**: [XposedInterface.getRemotePreferences] via
 *   [initForHooks] — framework-provided read-only view of the same file.
 *
 * ## Hot-path strategy
 * [isEnabled] is called on every hook invocation. Reading remote
 * preferences from disk on every call would add I/O to the main thread, so
 * the launcher side caches the value in an [AtomicBoolean] and refreshes
 * from disk at most once per [REFRESH_INTERVAL_MS]. A settings change takes
 * effect within one refresh interval — no launcher restart required.
 *
 * Failures default to `enabled = true` (never silently disable processing).
 */
object ConfigManager {

    private const val TAG = "MonoIcon.Config"
    private const val PREFS_NAME = "monoicon_config"
    private const val KEY_ENABLED = "enabled"

    /** Launcher-side refresh cadence (ms) for the cached enabled flag. */
    private const val REFRESH_INTERVAL_MS = 1_000L

    // ── UI process side (module's own process) ────────────────────────

    private var appPrefs: SharedPreferences? = null

    /** Binds the module-process SharedPreferences (call from Application/MainActivity). */
    fun init(context: Context) {
        try {
            appPrefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "init failed: ${t.message}")
        }
    }

    /** Reads the toggle from the UI process side. */
    fun isEnabledFromUi(): Boolean = appPrefs?.getBoolean(KEY_ENABLED, true) ?: true

    /** Writes the toggle from the UI process side. */
    fun setEnabled(enabled: Boolean) {
        try {
            appPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
            android.util.Log.i(TAG, "setEnabled=$enabled")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setEnabled failed: ${t.message}")
        }
    }

    // ── Launcher (hook) process side ──────────────────────────────────

    private var remotePrefsProvider: (() -> SharedPreferences?)? = null
    private val cachedEnabled = AtomicBoolean(true)
    @Volatile
    private var lastRefreshMs = 0L

    /**
     * Binds the framework's read-only remote preferences (call from
     * [io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam]
     * handling, before installing hooks).
     */
    fun initForHooks(api: XposedInterface) {
        try {
            remotePrefsProvider = { getRemotePrefs(api) }
            cachedEnabled.set(readRemote())
            lastRefreshMs = SystemClock.elapsedRealtime()
            android.util.Log.i(TAG, "hook-side init, enabled=${cachedEnabled.get()}")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "initForHooks failed: ${t.message}")
        }
    }

    /**
     * Hot-path check: cached flag, refreshed from disk at most once per
     * [REFRESH_INTERVAL_MS]. Pure memory read on the fast path.
     */
    fun isEnabled(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            lastRefreshMs = now
            cachedEnabled.set(readRemote())
        }
        return cachedEnabled.get()
    }

    private fun getRemotePrefs(api: XposedInterface): SharedPreferences? = try {
        api.getRemotePreferences(PREFS_NAME)
    } catch (t: Throwable) {
        null
    }

    private fun readRemote(): Boolean = try {
        remotePrefsProvider?.invoke()?.getBoolean(KEY_ENABLED, true) ?: true
    } catch (t: Throwable) {
        true
    }
}
