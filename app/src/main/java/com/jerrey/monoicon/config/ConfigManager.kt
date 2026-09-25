package com.jerrey.monoicon.config

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.jerrey.monoicon.cache.CacheManager
import com.jerrey.monoicon.theme.mask.MiuiIconShapeCompat
import com.jerrey.monoicon.theme.render.IconShape
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private const val KEY_THEME_ID = "theme_id"
    private const val KEY_VARIANT_ID = "variant_id"
    private const val KEY_LAWNICONS_ENABLED = "lawnicons_enabled"
    private const val KEY_CIRCLE_ICONS = "circle_icons"
    private const val KEY_NOTIFICATION_ICONS = "notification_icons"
    private const val KEY_LANGUAGE = "ui_language"

    /** Language ids: system default / Simplified Chinese / English. */
    private const val LANGUAGE_SYSTEM = "system"

    /** Launcher-side refresh cadence (ms). */
    private const val REFRESH_INTERVAL_MS = 1_000L

    // ── UI process side (module's own process) ────────────────────────

    private val mutableServiceState = MutableStateFlow<XposedService?>(null)

    /** The current service, updated by framework bind/death events without polling. */
    val serviceState: StateFlow<XposedService?> = mutableServiceState.asStateFlow()

    private val remoteService: XposedService? get() = mutableServiceState.value
    private var fallbackPrefs: SharedPreferences? = null

    /** Guards the one-shot UI-side initialization (MainActivity may be recreated). */
    private val uiInitDone = AtomicBoolean(false)

    /** Number of framework listener registrations performed (test/diagnostic). */
    private val uiInitRegistrations = AtomicInteger(0)

    /**
     * Registers for the framework service binder and binds the module
     * SharedPreferences as fallback. Call from Application/MainActivity.
     *
     * Idempotent: the activity is recreated on configuration changes, and each
     * call would otherwise register another [XposedServiceHelper.OnServiceListener]
     * that the framework keeps forever.
     */
    fun init(context: Context) {
        if (!uiInitDone.compareAndSet(false, true)) return
        try {
            fallbackPrefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    mutableServiceState.value = service
                    android.util.Log.i(TAG, "XposedService bound")
                }

                override fun onServiceDied(service: XposedService) {
                    if (mutableServiceState.compareAndSet(service, null)) {
                        android.util.Log.i(TAG, "XposedService died")
                    }
                }
            })
            uiInitRegistrations.incrementAndGet()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "init failed: ${t.message}")
        }
    }

    /** Test/diagnostic hook: how many times the framework listener was registered. */
    internal fun uiInitRegistrationCount(): Int = uiInitRegistrations.get()

    /**
     * The framework service bound in this (settings) process, or null when the
     * module is not activated in LSPosed. Used by the overview status card.
     */
    fun xposedService(): XposedService? = remoteService

    // ── UI language (Phase 13) ────────────────────────────────────────

    /**
     * Reads the UI language **directly from the module shared preferences**.
     *
     * Must work before [init] runs (it is called from
     * `MainActivity.attachBaseContext`, i.e. before `onCreate`), so it does not
     * use the framework service.
     */
    fun languageFromPrefs(context: Context): String = try {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, LANGUAGE_SYSTEM) ?: LANGUAGE_SYSTEM
    } catch (_: Throwable) {
        LANGUAGE_SYSTEM
    }

    /** UI side: the selected language id (`system` / `zh` / `en`). */
    fun getLanguageFromUi(): String = fallbackPrefs?.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM)
        ?: languageOrSystem()

    private fun languageOrSystem(): String = try {
        remoteService?.getRemotePreferences(PREFS_NAME)?.getString(KEY_LANGUAGE, LANGUAGE_SYSTEM)
            ?: LANGUAGE_SYSTEM
    } catch (_: Throwable) {
        LANGUAGE_SYSTEM
    }

    /**
     * Persists the language both in the module shared preferences (read by
     * `attachBaseContext`) and, when available, in the framework remote
     * preferences so the hook processes see the same value.
     */
    fun setLanguage(languageId: String) {
        try {
            fallbackPrefs?.edit()?.putString(KEY_LANGUAGE, languageId)?.apply()
            remoteService?.getRemotePreferences(PREFS_NAME)
                ?.edit()
                ?.putString(KEY_LANGUAGE, languageId)
                ?.apply()
            android.util.Log.i(TAG, "setLanguage=$languageId")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setLanguage failed: ${t.message}")
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
    private val lastRefreshMs = AtomicLong(0L)

    // ── Lawnicons bundle toggle (Phase 8) ──────────────────────────────
    private val cachedLawniconsEnabled = AtomicBoolean(true)
    private val lastLawniconsRefreshMs = AtomicLong(0L)
    @Volatile
    private var lastLawniconsValue = true

    // ── Circle icon shape toggle (Phase 9) ─────────────────────────────
    // Default OFF: the shape feature changes the desktop look globally, so it
    // stays opt-in and the code is a no-op until the user enables it.
    private val cachedCircleIconsEnabled = AtomicBoolean(false)
    private val lastCircleRefreshMs = AtomicLong(0L)
    @Volatile
    private var lastCircleValue = false

    // ── Notification app-icon toggle (Phase 11, SystemUI process) ──────
    // Default ON: notification app icons follow the desktop look; the switch
    // exists as an escape hatch because SystemUI is a critical process.
    private val cachedNotificationIconsEnabled = AtomicBoolean(true)
    private val lastNotificationIconsRefreshMs = AtomicLong(0L)
    @Volatile
    private var lastNotificationIconsValue = true

    /**
     * Binds the framework's read-only remote preferences (call from
     * PackageLoadedParam handling, before installing hooks).
     */
    fun initForHooks(api: XposedInterface) {
        try {
            remotePrefsProvider = { getRemotePrefs(api) }
            cachedEnabled.set(readRemote())
            val now = SystemClock.elapsedRealtime()
            lastRefreshMs.set(now)
            cachedLawniconsEnabled.set(readRemoteLawnicons())
            lastLawniconsValue = cachedLawniconsEnabled.get()
            lastLawniconsRefreshMs.set(now)
            cachedCircleIconsEnabled.set(readRemoteCircleIcons())
            lastCircleValue = cachedCircleIconsEnabled.get()
            lastCircleRefreshMs.set(now)
            cachedNotificationIconsEnabled.set(readRemoteNotificationIcons())
            lastNotificationIconsValue = cachedNotificationIconsEnabled.get()
            lastNotificationIconsRefreshMs.set(now)
            android.util.Log.i(
                TAG,
                "hook-side init, enabled=${cachedEnabled.get()} lawnicons=${cachedLawniconsEnabled.get()} " +
                    "circle=${cachedCircleIconsEnabled.get()} notif=${cachedNotificationIconsEnabled.get()}",
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "initForHooks failed: ${t.message}")
        }
    }

    /**
     * Hot-path check: cached flag, refreshed from the framework at most
     * once per [REFRESH_INTERVAL_MS]. Pure memory read on the fast path.
     */
    fun isEnabled(): Boolean {
        if (claimRefresh(lastRefreshMs)) {
            cachedEnabled.set(readRemote())
        }
        return cachedEnabled.get()
    }

    /** Only one hook thread refreshes a given flag in each existing time window. */
    private fun claimRefresh(lastRefresh: AtomicLong): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = lastRefresh.get()
        return now - previous >= REFRESH_INTERVAL_MS && lastRefresh.compareAndSet(previous, now)
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

    // ── Theme ID (Phase 5) ─────────────────────────────────────────────

    /** Launcher side: reads the persisted theme ID (defaults to pixel_default). */
    fun getThemeId(): String = try {
        remotePrefsProvider?.invoke()?.getString(KEY_THEME_ID, "pixel_default") ?: "pixel_default"
    } catch (t: Throwable) {
        "pixel_default"
    }

    /** UI side: reads the theme ID from remote preferences (falls back to shared prefs). */
    fun getThemeIdFromUi(): String {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME).getString(KEY_THEME_ID, "pixel_default")
                    ?: "pixel_default"
            } catch (t: Throwable) {
                fallbackPrefs?.getString(KEY_THEME_ID, "pixel_default") ?: "pixel_default"
            }
        } else {
            fallbackPrefs?.getString(KEY_THEME_ID, "pixel_default") ?: "pixel_default"
        }
    }

    /** Writes the theme ID via the framework remote preferences. */
    fun setThemeId(themeId: String) {
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putString(KEY_THEME_ID, themeId)
                    .apply()
                android.util.Log.i(TAG, "setThemeId=$themeId (remote)")
            } else {
                fallbackPrefs?.edit()?.putString(KEY_THEME_ID, themeId)?.apply()
                android.util.Log.i(TAG, "setThemeId=$themeId (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setThemeId failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putString(KEY_THEME_ID, themeId)?.apply()
            } catch (_: Throwable) { }
        }
    }

    // ── Material Dynamic Color Variant ────────────────────────────────

    /** Launcher side: reads the selected Google 2025 Variant. */
    fun getVariantId(): String = try {
        remotePrefsProvider?.invoke()?.getString(KEY_VARIANT_ID, "tonal_spot") ?: "tonal_spot"
    } catch (_: Throwable) {
        "tonal_spot"
    }

    /** UI side: reads the selected Variant from remote preferences. */
    fun getVariantIdFromUi(): String {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME)
                    .getString(KEY_VARIANT_ID, "tonal_spot") ?: "tonal_spot"
            } catch (_: Throwable) {
                fallbackPrefs?.getString(KEY_VARIANT_ID, "tonal_spot") ?: "tonal_spot"
            }
        } else {
            fallbackPrefs?.getString(KEY_VARIANT_ID, "tonal_spot") ?: "tonal_spot"
        }
    }

    /** Persists the selected Google 2025 Variant. */
    fun setVariantId(variantId: String) {
        val normalized = variantId.trim().lowercase().ifBlank { "tonal_spot" }
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putString(KEY_VARIANT_ID, normalized)
                    .apply()
                android.util.Log.i(TAG, "setVariantId=$normalized (remote)")
            } else {
                fallbackPrefs?.edit()?.putString(KEY_VARIANT_ID, normalized)?.apply()
                android.util.Log.i(TAG, "setVariantId=$normalized (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setVariantId failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putString(KEY_VARIANT_ID, normalized)?.apply()
            } catch (_: Throwable) { }
        }
    }

    // ── Lawnicons bundle toggle (Phase 8) ─────────────────────────────

    /**
     * Launcher side: whether the built-in Lawnicons mask bundle may be used
     * for icons without a native monochrome layer.
     *
     * Refresh cadence matches [isEnabled]. When the value flips, every cached
     * mask was produced by the other branch, so the mask caches are cleared
     * once — otherwise the change would only take effect for new icons.
     */
    fun isLawniconsEnabled(): Boolean {
        if (claimRefresh(lastLawniconsRefreshMs)) {
            val value = readRemoteLawnicons()
            if (value != lastLawniconsValue) {
                lastLawniconsValue = value
                try {
                    CacheManager.clearAll()
                    android.util.Log.i(TAG, "lawniconsEnabled=$value → mask caches cleared")
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "cache clear after toggle failed: ${t.message}")
                }
            }
            cachedLawniconsEnabled.set(value)
        }
        return cachedLawniconsEnabled.get()
    }

    private fun readRemoteLawnicons(): Boolean = try {
        remotePrefsProvider?.invoke()?.getBoolean(KEY_LAWNICONS_ENABLED, true) ?: true
    } catch (_: Throwable) {
        true
    }

    /** UI side: reads the Lawnicons toggle (falls back to shared prefs). */
    fun isLawniconsEnabledFromUi(): Boolean {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME).getBoolean(KEY_LAWNICONS_ENABLED, true)
            } catch (_: Throwable) {
                fallbackPrefs?.getBoolean(KEY_LAWNICONS_ENABLED, true) ?: true
            }
        } else {
            fallbackPrefs?.getBoolean(KEY_LAWNICONS_ENABLED, true) ?: true
        }
    }

    /** Persists the Lawnicons toggle via the framework remote preferences. */
    fun setLawniconsEnabled(enabled: Boolean) {
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putBoolean(KEY_LAWNICONS_ENABLED, enabled)
                    .apply()
                android.util.Log.i(TAG, "setLawniconsEnabled=$enabled (remote)")
            } else {
                fallbackPrefs?.edit()?.putBoolean(KEY_LAWNICONS_ENABLED, enabled)?.apply()
                android.util.Log.i(TAG, "setLawniconsEnabled=$enabled (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setLawniconsEnabled failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putBoolean(KEY_LAWNICONS_ENABLED, enabled)?.apply()
            } catch (_: Throwable) { }
        }
    }

    // ── Circle icon shape toggle (Phase 9) ────────────────────────────

    /**
     * Launcher side: whether icons are rendered with the circular silhouette
     * (MIUI `IconCustomizer` config hijack + MonoIcon drawable clip), without
     * installing an MTZ theme.
     *
     * Refresh cadence matches [isEnabled]. On a flip, MIUI's own icon caches
     * are cleared (which also resets the parsed `IconConfig`, so the next
     * composition picks up the new mask) together with MonoIcon's caches.
     * Icons already rendered keep their old shape until the launcher rebinds
     * them — the settings UI therefore shows the restart hint.
     */
    fun isCircleIconsEnabled(): Boolean {
        if (claimRefresh(lastCircleRefreshMs)) {
            val value = readRemoteCircleIcons()
            if (value != lastCircleValue) {
                lastCircleValue = value
                try {
                    CacheManager.clearAll()
                    MiuiIconShapeCompat.invalidateMiuiCaches()
                    android.util.Log.i(TAG, "circleIcons=$value → icon caches cleared")
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "cache clear after circle flip failed: ${t.message}")
                }
            }
            cachedCircleIconsEnabled.set(value)
        }
        return cachedCircleIconsEnabled.get()
    }

    /** Silhouette to use for MonoIcon's own drawables right now. */
    fun iconShape(): IconShape =
        if (isCircleIconsEnabled()) IconShape.CIRCLE else IconShape.SQUIRCLE

    private fun readRemoteCircleIcons(): Boolean = try {
        remotePrefsProvider?.invoke()?.getBoolean(KEY_CIRCLE_ICONS, false) ?: false
    } catch (_: Throwable) {
        false
    }

    /** UI side: reads the circle-icon toggle (falls back to shared prefs). */
    fun isCircleIconsEnabledFromUi(): Boolean {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME).getBoolean(KEY_CIRCLE_ICONS, false)
            } catch (_: Throwable) {
                fallbackPrefs?.getBoolean(KEY_CIRCLE_ICONS, false) ?: false
            }
        } else {
            fallbackPrefs?.getBoolean(KEY_CIRCLE_ICONS, false) ?: false
        }
    }

    /** Persists the circle-icon toggle via the framework remote preferences. */
    fun setCircleIconsEnabled(enabled: Boolean) {
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putBoolean(KEY_CIRCLE_ICONS, enabled)
                    .apply()
                android.util.Log.i(TAG, "setCircleIconsEnabled=$enabled (remote)")
            } else {
                fallbackPrefs?.edit()?.putBoolean(KEY_CIRCLE_ICONS, enabled)?.apply()
                android.util.Log.i(TAG, "setCircleIconsEnabled=$enabled (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setCircleIconsEnabled failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putBoolean(KEY_CIRCLE_ICONS, enabled)?.apply()
            } catch (_: Throwable) { }
        }
    }

    // ── Notification app-icon toggle (Phase 11, SystemUI process) ─────

    /**
     * SystemUI side: whether notification app icons are rendered with MonoIcon's
     * themed drawable (`AppIconsManager` hooks).
     *
     * Refresh cadence matches [isEnabled]; on a flip MonoIcon's caches are cleared so
     * the next notification bind regenerates the icon. Already-rendered notifications
     * (and MIUI's own bitmap caches) keep the old icon until SystemUI restarts — the
     * settings UI shows the restart hint.
     */
    fun isNotificationIconsEnabled(): Boolean {
        if (claimRefresh(lastNotificationIconsRefreshMs)) {
            val value = readRemoteNotificationIcons()
            if (value != lastNotificationIconsValue) {
                lastNotificationIconsValue = value
                try {
                    CacheManager.clearAll()
                    android.util.Log.i(TAG, "notificationIcons=$value → caches cleared")
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "cache clear after notif flip failed: ${t.message}")
                }
            }
            cachedNotificationIconsEnabled.set(value)
        }
        return cachedNotificationIconsEnabled.get()
    }

    private fun readRemoteNotificationIcons(): Boolean = try {
        remotePrefsProvider?.invoke()?.getBoolean(KEY_NOTIFICATION_ICONS, true) ?: true
    } catch (_: Throwable) {
        true
    }

    /** UI side: reads the notification-icon toggle (falls back to shared prefs). */
    fun isNotificationIconsEnabledFromUi(): Boolean {
        val service = remoteService
        return if (service != null) {
            try {
                service.getRemotePreferences(PREFS_NAME).getBoolean(KEY_NOTIFICATION_ICONS, true)
            } catch (_: Throwable) {
                fallbackPrefs?.getBoolean(KEY_NOTIFICATION_ICONS, true) ?: true
            }
        } else {
            fallbackPrefs?.getBoolean(KEY_NOTIFICATION_ICONS, true) ?: true
        }
    }

    /** Persists the notification-icon toggle via the framework remote preferences. */
    fun setNotificationIconsEnabled(enabled: Boolean) {
        try {
            val service = remoteService
            if (service != null) {
                service.getRemotePreferences(PREFS_NAME)
                    .edit()
                    .putBoolean(KEY_NOTIFICATION_ICONS, enabled)
                    .apply()
                android.util.Log.i(TAG, "setNotificationIconsEnabled=$enabled (remote)")
            } else {
                fallbackPrefs?.edit()?.putBoolean(KEY_NOTIFICATION_ICONS, enabled)?.apply()
                android.util.Log.i(TAG, "setNotificationIconsEnabled=$enabled (fallback prefs)")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "setNotificationIconsEnabled failed: ${t.message}")
            try {
                fallbackPrefs?.edit()?.putBoolean(KEY_NOTIFICATION_ICONS, enabled)?.apply()
            } catch (_: Throwable) { }
        }
    }
}
