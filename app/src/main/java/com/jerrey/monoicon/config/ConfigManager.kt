package com.jerrey.monoicon.config

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime configuration bridge between the settings UI and the hook runtime
 * (Phase 4.1).
 *
 * ## Process model
 * The settings UI runs in the module's own process; the hooks run inside the
 * HyperOS launcher process. Both sides access the same remote preferences
 * managed by the LSPosed framework:
 *
 * - **UI process**: [init] registers an [XposedServiceHelper] listener.
 *   When the framework delivers the binder (SEND_BINDER), [setEnabled]
 *   writes through `service.getRemotePreferences(...).edit()`.
 * - **Launcher process**: [initForHooks] reads via
 *   [XposedInterface.getRemotePreferences] — the framework provides a
 *   read-only view of the same store.
 *
 * ## Hot-path strategy
 * [isEnabled] is called on every hook invocation. The launcher side caches
 * the value in an [AtomicBoolean] and refreshes at most once per
 * [REFRESH_INTERVAL_MS]. A settings change takes effect within one refresh
 * interval — no launcher restart required.
 *
 * Failures default to `enabled = true` (never silently disable processing).
 */
object ConfigManager {

    private const val TAG = "MonoIcon.Config"
    private const val PREFS_NAME = "monoicon_config"
    private const val KEY_ENABLED = "enabled"

    /** Launcher-side refresh cadence (ms). */
    private const val REFRESH_INTERVAL_MS = 1_000L

    // ── UI process side (module's own process) ────────────────────────

    @Volatile
    private var remoteService: XposedService? = null
    private var fallbackPrefs: SharedPreferences? = null

    /**
     * Registers for the framework service binder and binds the module
     * SharedPreferences as fallback. Call from Application/MainActivity.
     */
    fun init(context: Context) {
        try {
            fallbackPrefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    remoteService = service
                    android.util.Log.i(TAG, "XposedService bound")
                }

                override fun onServiceDied(service: XposedService) {
                    if (remoteService === service) {
                        remoteService = null
                        android.util.Log.i(TAG, "XposedService died")
                    }
                }
            })
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "init failed: ${t.message}")
        }
    }

    /** Reads the toggle (UI side; falls back to shared prefs). */
    fun isEnabledFromUi(): Boolean {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME).getBoolean(KEY_ENABLED, true)
            } catch (t: Throwable) {
                fallbackPrefs?.getBoolean(KEY_ENABLED, true) ?: true
            }
        } else {
            fallbackPrefs?.getBoolean(KEY_ENABLED, true) ?: true
        }
    }

    /** Writes the toggle via the framework remote preferences. Falls back to module shared prefs. */
    fun setEnabled(enabled: Boolean) {
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putBoolean(KEY_ENABLED, enabled)
                    .apply()
                android.util.Log.i(TAG, "setEnabled=$enabled (remote)")
            } else {
                fallbackPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
                android.util.Log.i(TAG, "setEnabled=$enabled (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setEnabled failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
            } catch (_: Throwable) { }
        }
    }

    // ── Launcher (hook) process side ──────────────────────────────────

    private var remotePrefsProvider: (() -> SharedPreferences?)? = null
    private val cachedEnabled = AtomicBoolean(true)
    @Volatile
    private var lastRefreshMs = 0L

    /**
     * Binds the framework's read-only remote preferences (call from
     * PackageLoadedParam handling, before installing hooks).
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
     * Hot-path check: cached flag, refreshed from the framework at most
     * once per [REFRESH_INTERVAL_MS]. Pure memory read on the fast path.
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
