package com.jerrey.monoicon.theme.color.dynamic

import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.theme.IconContext

/**
 * Reads the system Material You dynamic accent color from Android
 * framework resources (Phase 6.1).
 *
 * ## Color selection
 * Uses `android.R.color.system_accent1_*` resources (available on
 * Android 12+ with Material You enabled). Tone selection follows
 * Pixel Launcher convention:
 * - Light mode: accent1 tone 400 (darker, readable on light bg)
 * - Dark mode:  accent1 tone 600 (lighter, readable on dark bg)
 *
 * ## Cache
 * A single color is cached with a 60-second TTL. The system palette
 * changes only on wallpaper update, so the cache hit rate is
 * effectively 100% in steady state. Re-querying on every icon bind
 * (the hot path) is a single `SystemClock` check — no IPC, no disk.
 *
 * ## Fallback
 * If system resources are unavailable (API < 31, non-Material ROM,
 * removed APK resources), returns a neutral dark-gray
 * (`0xFF3C4043`) that renders as readable black/white on most
 * launcher backgrounds without looking broken.
 */
object SystemMaterialColorProvider : DynamicColorProvider {

    private const val TAG = "MonoIcon.DynamicColor"

    /** 60-second TTL — the system palette changes only on wallpaper switch. */
    private const val CACHE_TTL_MS = 60_000L

    /** Safe neutral gray when system colors are unavailable. */
    private const val FALLBACK_COLOR = 0xFF3C4043.toInt()

    @Volatile
    private var cachedColor: Int = FALLBACK_COLOR

    @Volatile
    private var lastFetchMs = 0L

    override fun getIconTint(identity: String?, context: IconContext): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFetchMs < CACHE_TTL_MS) {
            return cachedColor
        }
        synchronized(this) {
            if (now - lastFetchMs < CACHE_TTL_MS) {
                return cachedColor
            }
            cachedColor = fetchSystemColor()
            lastFetchMs = SystemClock.elapsedRealtime()
            logd(TAG, "refreshed color=0x${Integer.toHexString(cachedColor)}")
        }
        return cachedColor
    }

    /** Forces a refresh on next call (ConfigManager lifecycle). */
    fun invalidate() {
        lastFetchMs = 0
    }

    private fun fetchSystemColor(): Int {
        return try {
            val res = Resources.getSystem()
            val isDark = (res.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

            // Pixel Launcher convention: lighter tone for dark mode,
            // darker tone for light mode — ensures contrast against
            // the launcher background.
            val toneId = if (isDark) {
                android.R.color.system_accent1_600
            } else {
                android.R.color.system_accent1_400
            }

            @Suppress("DEPRECATION")
            val color = res.getColor(toneId)
            if (color and 0xFF000000.toInt() == 0) FALLBACK_COLOR else color
        } catch (t: Throwable) {
            // Resource not found, API < 31, or non-Material ROM
            FALLBACK_COLOR
        }
    }
}
