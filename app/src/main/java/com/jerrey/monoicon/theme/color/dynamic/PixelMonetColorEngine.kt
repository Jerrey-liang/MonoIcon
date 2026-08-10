package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.SystemClock
import com.jerrey.monoicon.logging.logd

/**
 * Material You Monet color engine (Phase 6.2).
 *
 * ## Pixel Launcher compatibility
 * Pixel Launcher uses `ColorScheme(wallpaperColors)` → custom resource
 * mapping → `themed_icon_background_color` + `themed_icon_color`.
 * This engine approximates that pipeline without depending on SystemUI
 * monet classes:
 * 1. Reads seed color from [WallpaperColors.getPrimaryColor]
 * 2. Generates approximate tonal shades (linear RGB interpolation
 *    toward white for lighter tones, black for darker tones)
 * 3. Selects foreground/background based on dark mode
 *
 * ## Tone selection
 * - Light mode: fg = tone 40 (dark icon on light surface),
 *               bg = tone 95 (near-surface light fill)
 * - Dark mode:  fg = tone 80 (light icon on dark surface),
 *               bg = tone 10 (near-surface dark fill)
 *
 * ## Cache
 * 60-second TTL single-value cache — wallpaper changes require
 * system broadcast; TTL provides eventual consistency without IPC.
 * [invalidate] forces immediate re-read.
 *
 * Must call [init] with a launcher [Context] before first use.
 */
object PixelMonetColorEngine {

    private const val TAG = "MonoIcon.Monet"
    private const val CACHE_TTL_MS = 60_000L

    /** Safe neutral when wallpaper / seed is unavailable (no HCT dependency). */
    private val FALLBACK = IconThemeColors(
        foreground = 0xFF3C4043.toInt(),
        background = 0xFFF5F5F5.toInt(),
        palette = MonetPalette(
            TonePalette.generate(0xFF4A6D8C.toInt()),
            TonePalette.generate(0xFF4A6D8C.toInt()),
            TonePalette.generate(0xFF4A6D8C.toInt()),
            TonePalette.generate(0xFF4A6D8C.toInt()),
            TonePalette.generate(0xFF4A6D8C.toInt()),
        )
    )

    @Volatile private var cachedColors: IconThemeColors = FALLBACK
    @Volatile private var lastFetchMs = 0L
    @Volatile private var wallpaperManager: WallpaperManager? = null
    private val lock = Any()

    // ── Lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        if (wallpaperManager != null) return
        wallpaperManager = WallpaperManager.getInstance(context.applicationContext)
        logd(TAG, "initialized")
    }

    fun invalidate() {
        lastFetchMs = 0
        logd(TAG, "invalidated")
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun getIconColors(): IconThemeColors {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFetchMs < CACHE_TTL_MS) {
            return cachedColors
        }
        synchronized(lock) {
            // Re-check inside lock — may have been filled by another thread
            val recheck = SystemClock.elapsedRealtime()
            if (recheck - lastFetchMs < CACHE_TTL_MS) {
                return cachedColors
            }
            val colors = computeColors()
            cachedColors = colors
            lastFetchMs = SystemClock.elapsedRealtime()
            return colors
        }
    }

    // ── Color computation ──────────────────────────────────────────────

    private fun computeColors(): IconThemeColors {
        val wm = wallpaperManager ?: return FALLBACK
        return try {
            @Suppress("NewApi", "DEPRECATION")
            val wallpaperColors: WallpaperColors = try {
                wm.getWallpaperColors(1 /* FLAG_SYSTEM */)
            } catch (_: Throwable) {
                null
            } ?: return FALLBACK

            // Phase 6.3: seed selection with chroma floor
            val seed = WallpaperSeedSelector.select(wallpaperColors)
            val palette = MonetPalette.generate(seed)
            val isDark = isSystemDark()

            val fgToneIdx = if (isDark) 8 else 4    // tone 80 / tone 40
            val bgToneIdx = if (isDark) 1 else 11   // tone 10 / tone 95

            logd(TAG, "seed=0x${Integer.toHexString(seed)} fgTone=$fgToneIdx bgTone=$bgToneIdx dark=$isDark")
            IconThemeColors(
                foreground = palette.accent1[fgToneIdx],
                background = palette.accent1[bgToneIdx],
                palette = palette
            )
        } catch (t: Throwable) {
            FALLBACK
        }
    }

    private fun isSystemDark(): Boolean = try {
        (Resources.getSystem().configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        false
    }
}
