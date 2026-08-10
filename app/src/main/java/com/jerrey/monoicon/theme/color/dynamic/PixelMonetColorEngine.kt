package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
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

    /** Safe neutral when wallpaper / seed is unavailable. */
    private val FALLBACK = IconThemeColors(
        background = 0xFFF5F5F5.toInt(),
        foreground = 0xFF3C4043.toInt()
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

            // Primary seed color (Monet accent1 seed)
            val seed = wallpaperColors.primaryColor?.toArgb() ?: return FALLBACK

            val isDark = isSystemDark()

            val fgTone = if (isDark) 80f else 40f
            val bgTone = if (isDark) 10f else 95f

            logd(TAG, "seed=0x${Integer.toHexString(seed)} fgTone=$fgTone bgTone=$bgTone dark=$isDark")
            IconThemeColors(
                background = generateTone(seed, bgTone),
                foreground = generateTone(seed, fgTone)
            )
        } catch (t: Throwable) {
            FALLBACK
        }
    }

    /**
     * Approximates a Monet tonal shade via linear RGB interpolation.
     *
     * @param seedColor  ARGB seed (primary color from wallpaper).
     * @param tone       0 = black, 100 = white (perceptual lightness).
     */
    private fun generateTone(seedColor: Int, tone: Float): Int {
        if (tone <= 0f) return Color.BLACK
        if (tone >= 100f) return Color.WHITE

        val r = Color.red(seedColor)
        val g = Color.green(seedColor)
        val b = Color.blue(seedColor)

        // Convert tone (perceptual) to approximate sRGB luminance
        val targetLum = tone * 2.55f  // tone 0→0, tone 100→255
        val seedLum = (0.299f * r + 0.587f * g + 0.114f * b)

        val tr: Int
        val tg: Int
        val tb: Int

        if (targetLum > seedLum) {
            // Lighten: blend toward white
            val ratio = ((targetLum - seedLum) / (255f - seedLum)).coerceIn(0f, 1f)
            tr = (r + (255 - r) * ratio).toInt()
            tg = (g + (255 - g) * ratio).toInt()
            tb = (b + (255 - b) * ratio).toInt()
        } else {
            // Darken: blend toward black
            val ratio = ((seedLum - targetLum) / seedLum).coerceIn(0f, 1f)
            tr = (r * (1f - ratio)).toInt()
            tg = (g * (1f - ratio)).toInt()
            tb = (b * (1f - ratio)).toInt()
        }

        return Color.rgb(
            tr.coerceIn(0, 255),
            tg.coerceIn(0, 255),
            tb.coerceIn(0, 255)
        )
    }

    private fun isSystemDark(): Boolean = try {
        (Resources.getSystem().configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        false
    }
}
