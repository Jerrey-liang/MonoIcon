package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
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
    @Volatile private var appContext: Context? = null
    @Volatile private var lastDarkMode: Boolean? = null
    @Volatile private var colorsChangedListener: WallpaperManager.OnColorsChangedListener? = null
    private val lock = Any()

    // ── Lifecycle ──────────────────────────────────────────────────────

    fun init(context: Context) {
        if (wallpaperManager != null) return
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val manager = WallpaperManager.getInstance(applicationContext)
        wallpaperManager = manager
        try {
            val listener = WallpaperManager.OnColorsChangedListener { _, which ->
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) invalidate()
            }
            manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            colorsChangedListener = listener
        } catch (_: Throwable) {
            // TTL remains as a safe fallback if the listener is unavailable.
        }
        logd(TAG, "initialized")
    }

    fun invalidate() {
        lastFetchMs = 0
        lastDarkMode = null
        logd(TAG, "invalidated")
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun getIconColors(): IconThemeColors {
        // Configuration changes (especially light/dark mode) must not be
        // hidden behind the normal TTL. Wallpaper changes are invalidated by
        // the package/launcher lifecycle or by the next TTL refresh.
        val dark = isSystemDark()
        if (lastDarkMode != dark) {
            lastDarkMode = dark
            lastFetchMs = 0L
        }
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
            val isDark = isSystemDark()

            // Pixel Launcher exposes themed_icon_* resources. HyperOS uses
            // the same Monet tones under material_dynamic_primary* names.
            // Prefer either resource set over the local solver so the plate
            // follows the host system's actual overlay values.
            val launcherColors = readLauncherThemedIconColors(seed)
            if (launcherColors != null) {
                return IconThemeColors(
                    foreground = launcherColors.first,
                    background = launcherColors.second,
                    palette = MonetPalette.generate(seed),
                )
            }

            val hostMonetColors = readHostMonetColors(isDark, seed)
            if (hostMonetColors != null) {
                return IconThemeColors(
                    foreground = hostMonetColors.first,
                    background = hostMonetColors.second,
                    palette = MonetPalette.generate(seed),
                )
            }

            // Android 12+ exposes the same wallpaper-backed dynamic accent
            // palette through public system resources. Reading these values
            // avoids reimplementing SystemUI's Monet/HCT solver and, unlike
            // the old local solver, preserves the wallpaper hue for the plate.
            val systemColors = readSystemAccentColors(isDark, seed)
            if (systemColors != null) {
                return IconThemeColors(
                    foreground = systemColors.first,
                    background = systemColors.second,
                    palette = MonetPalette.generate(seed),
                )
            }

            val palette = MonetPalette.generate(seed)

            val fgToneIdx = if (isDark) 8 else 4    // tone 80 / tone 40 (themed_icon_color)
            val bgToneIdx = if (isDark) 1 else 10   // tone 10 / tone 95 (themed_icon_background_color)

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

    /**
     * Reads Android's wallpaper-backed accent tones.
     *
     * Resource mapping follows Material dynamic color tones:
     * accent1_600 = tone 40, accent1_50 = tone 95,
     * accent1_200 = tone 80, accent1_900 = tone 10.
     */
    private fun readSystemAccentColors(isDark: Boolean, seed: Int): Pair<Int, Int>? {
        val context = appContext ?: return null
        return try {
            val foregroundId = if (isDark) {
                android.R.color.system_accent1_200
            } else {
                android.R.color.system_accent1_600
            }
            val backgroundId = if (isDark) {
                android.R.color.system_accent1_900
            } else {
                android.R.color.system_accent1_50
            }
            val foreground = context.getColor(foregroundId)
            val background = context.getColor(backgroundId)
            if ((foreground ushr 24) == 0 || (background ushr 24) == 0) null
            else if (isDynamicColorAligned(seed, foreground)) foreground to background
            else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Reads Launcher-owned dynamic colors when HyperOS exposes Pixel names. */
    private fun readLauncherThemedIconColors(seed: Int): Pair<Int, Int>? {
        val context = appContext ?: return null
        return try {
            val resources = context.resources
            val packageName = context.packageName
            val foregroundId = resources.getIdentifier(
                "themed_icon_color", "color", packageName
            )
            val backgroundId = resources.getIdentifier(
                "themed_icon_background_color", "color", packageName
            )
            if (foregroundId == 0 || backgroundId == 0) return null
            val foreground = context.getColor(foregroundId)
            val background = context.getColor(backgroundId)
            if ((foreground ushr 24) == 0 || (background ushr 24) == 0) return null
            if (isDynamicColorAligned(seed, foreground)) foreground to background else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * HyperOS keeps its wallpaper-backed Monet palette in launcher resources
     * named `material_dynamic_primary{tone}`.  These are the same tone roles
     * Pixel maps to `themed_icon_color` and `themed_icon_background_color`.
     */
    private fun readHostMonetColors(isDark: Boolean, seed: Int): Pair<Int, Int>? {
        val context = appContext ?: return null
        return try {
            val resources = context.resources
            val packageName = context.packageName
            fun colorFor(tone: Int): Int {
                val id = resources.getIdentifier(
                    "material_dynamic_primary$tone", "color", packageName
                )
                if (id == 0) return 0
                return context.getColor(id)
            }
            val foreground = colorFor(if (isDark) 80 else 40)
            val background = colorFor(if (isDark) 10 else 95)
            if ((foreground ushr 24) == 0 || (background ushr 24) == 0) null
            else if (isDynamicColorAligned(seed, foreground)) {
                logd(TAG, "source=host_monet fg=0x${Integer.toHexString(foreground)} " +
                    "bg=0x${Integer.toHexString(background)} dark=$isDark")
                foreground to background
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Rejects a stale/static framework accent that does not follow the wallpaper hue. */
    private fun isDynamicColorAligned(seed: Int, foreground: Int): Boolean {
        val seedHsv = FloatArray(3)
        val fgHsv = FloatArray(3)
        android.graphics.Color.colorToHSV(seed, seedHsv)
        android.graphics.Color.colorToHSV(foreground, fgHsv)
        val seedChroma = seedHsv[1]
        if (seedChroma < 0.08f) return true
        if (fgHsv[1] < 0.05f) return false
        val distance = kotlin.math.abs(seedHsv[0] - fgHsv[0])
        val circularDistance = minOf(distance, 360f - distance)
        return circularDistance <= 55f
    }

    private fun isSystemDark(): Boolean = try {
        (Resources.getSystem().configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    } catch (_: Throwable) {
        false
    }
}
