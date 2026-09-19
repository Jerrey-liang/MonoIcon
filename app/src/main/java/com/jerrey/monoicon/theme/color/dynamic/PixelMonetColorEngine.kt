package com.jerrey.monoicon.theme.color.dynamic

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.material2025.quantize.QuantizerCelebi
import com.jerrey.monoicon.material2025.score.Score

/** Runtime adapter for the independent Google Material Color Utilities 2025 engine. */
object PixelMonetColorEngine {
    private const val TAG = "MonoIcon.Monet"
    private const val WHICH_SYSTEM = WallpaperManager.FLAG_SYSTEM
    private const val MIUI_MANAGER = "com.miui.miwallpaper.MiuiWallpaperManager"
    private const val FALLBACK_SOURCE = 0xFF4285F4.toInt()

    /** Signature|privileged permission required to read the wallpaper image. */
    private const val PERMISSION_READ_WALLPAPER = "android.permission.READ_WALLPAPER_INTERNAL"

    /** How long a quantised wallpaper seed is reused before re-reading the image. */
    private const val SEED_TTL_MS = 10_000L

    @Volatile private var cachedWallpaperSeed: Int? = null
    @Volatile private var cachedWallpaperSeedAt: Long = 0L

    private val LIGHT_FALLBACK = IconThemeColors(0xFF3C4043.toInt(), 0xFFF5F5F5.toInt(), fallbackPalette(FALLBACK_SOURCE))
    private val DARK_FALLBACK = IconThemeColors(0xFFDADCE0.toInt(), 0xFF202124.toInt(), fallbackPalette(FALLBACK_SOURCE))

    @Volatile private var launcherContext: Context? = null
    @Volatile private var lastLoggedColors: Long = Long.MIN_VALUE
    @Volatile private var lastLoggedWallpaper: String? = null

    fun init(context: Context) {
        launcherContext = context.applicationContext
        logd(
            TAG,
            "initialized package=${launcherContext?.packageName} spec=2025 " +
                "variant=${ConfigManager.getVariantId()}",
        )
        logWallpaperSnapshot(launcherContext!!, "init")
    }

    fun invalidate() {
        lastLoggedColors = Long.MIN_VALUE
        lastLoggedWallpaper = null
        cachedWallpaperSeed = null
        cachedWallpaperSeedAt = 0L
        logd(TAG, "invalidated")
    }

    fun getIconColors(): IconThemeColors {
        val context = launcherContext ?: return LIGHT_FALLBACK
        val dark = isSystemDark(context)
        val variantId = ConfigManager.getVariantId()
        readMiuiWallpaperColors()?.let { return calculate("miui_wallpaper_service", it, dark, variantId) }
        readFrameworkWallpaperColors(context)?.let { return calculate("android_wallpaper_manager", it, dark, variantId) }
        logd(TAG, "source=material2025_fixed_fallback dark=$dark sourceColor=${format(FALLBACK_SOURCE)}")
        return if (dark) DARK_FALLBACK else LIGHT_FALLBACK
    }

    /**
     * The wallpaper seed colour this engine derives the icon palette from, or
     * [FALLBACK_SOURCE] when no wallpaper palette is readable.
     *
     * The settings UI passes this to `ThemeController(keyColor = …)` so the UI
     * palette and the icon palette are generated from the SAME seed — the
     * single-source requirement for the Dynamic Color pipeline. Returns the
     * raw seed (not a scheme role), because re-seeding from an already-derived
     * role would shift the tone.
     */
    fun getSeedColor(): Int {
        val context = launcherContext ?: return FALLBACK_SOURCE
        // Preferred: quantise the wallpaper image itself, which is what Material
        // Color Utilities means by "the wallpaper's colour". Needs
        // READ_WALLPAPER_INTERNAL; silently skipped when not granted.
        wallpaperSeed(context)?.let { return it }
        readMiuiWallpaperColors()?.let { return selectSourceColor(it) ?: FALLBACK_SOURCE }
        readFrameworkWallpaperColors(context)?.let { return selectSourceColor(it) ?: FALLBACK_SOURCE }
        return FALLBACK_SOURCE
    }

    /** Wallpaper bitmap for the given flag, or null when unreadable/unpermitted. */
    private fun wallpaperBitmap(context: Context, which: Int): android.graphics.Bitmap? = try {
        val granted = context.checkSelfPermission(PERMISSION_READ_WALLPAPER) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val file = if (granted) {
            WallpaperManager.getInstance(context).getWallpaperFile(which)
        } else {
            null
        }
        if (file == null) null else android.graphics.BitmapFactory.decodeFileDescriptor(file.fileDescriptor)
    } catch (_: Throwable) {
        null
    }

    /**
     * Seed taken from the wallpaper image via [seedFromBitmap], memoised for
     * [SEED_TTL_MS]. The bitmap decode plus a 128-colour quantisation is far too
     * heavy to repeat per call — `getSeedColor()` runs during composition and on
     * every icon that needs a palette.
     */
    private fun wallpaperSeed(context: Context): Int? {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedWallpaperSeed
        if (cached != null && now - cachedWallpaperSeedAt < SEED_TTL_MS) return cached

        val bitmap = wallpaperBitmap(context, WHICH_SYSTEM) ?: return cached
        return try {
            seedFromBitmap(bitmap)?.also {
                cachedWallpaperSeed = it
                cachedWallpaperSeedAt = now
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculate(source: String, colors: WallpaperColors, dark: Boolean, variantId: String): IconThemeColors {
        logWallpaper(colors, source)
        val sourceColor = selectSourceColor(colors)
        if (sourceColor == null) return if (dark) DARK_FALLBACK else LIGHT_FALLBACK
        val result = Material2025ColorEngine.iconColorsForSource(
            sourceColor,
            dark,
            Material2025ColorEngine.variantFromId(variantId),
        )
        logColors(source, dark, variantId, sourceColor, result.foreground, result.background)
        return result
    }

    private fun selectSourceColor(colors: WallpaperColors): Int? {
        val candidates = listOf(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor)
        return candidates.asSequence().filterNotNull().map { it.toArgb() }
            .firstOrNull { com.jerrey.monoicon.material2025.hct.Hct.fromInt(it).chroma >= 5.0 }
            ?: candidates.asSequence().filterNotNull().map { it.toArgb() }.firstOrNull()
    }

    /**
     * The wallpaper's colour as Material Color Utilities defines it: quantise the
     * image with Celebi, then take the top colour from `Score` (population 0.7 /
     * chroma 0.3, see `Score.kt`).
     *
     * This is NOT the same as `WallpaperColors.primaryColor`. Measured against the
     * reference wallpaper the framework reports `FFA592D3` — a washed-out purple —
     * while the quantised seed is `FF706496`. Anything that reads the framework's
     * trio is therefore a different colour from what Material 2025 would pick.
     *
     * @return the seed, or null when there is nothing rankable.
     */
    fun seedFromBitmap(bitmap: android.graphics.Bitmap): Int? = try {
        val width = 128
        val height = (bitmap.height.toFloat() / bitmap.width * width).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        val population = QuantizerCelebi.quantize(pixels, 128)
        small.recycle()
        if (population.isEmpty()) null else Score.score(population, 1).firstOrNull()
    } catch (_: Throwable) {
        null
    }

    private fun readMiuiWallpaperColors(): WallpaperColors? = try {
        val type = Class.forName(MIUI_MANAGER)
        val field = type.getDeclaredField("sInstance").apply { isAccessible = true }
        val instance = field.get(null) ?: return null
        type.getMethod("getMiuiWallpaperColors", Int::class.javaPrimitiveType)
            .invoke(instance, WHICH_SYSTEM) as? WallpaperColors
    } catch (t: Throwable) {
        logd(TAG, "miui_wallpaper_service unavailable=${t.javaClass.simpleName}")
        null
    }

    private fun readFrameworkWallpaperColors(context: Context): WallpaperColors? = try {
        WallpaperManager.getInstance(context).getWallpaperColors(WHICH_SYSTEM)
    } catch (t: Throwable) {
        logd(TAG, "android_wallpaper_manager unavailable=${t.javaClass.simpleName}")
        null
    }

    private fun logColors(source: String, dark: Boolean, variantId: String, sourceColor: Int, foreground: Int, background: Int) {
        if (!LogcatLogger.isDebugEnabled) return
        val packed = (foreground.toLong() shl 32) xor (background.toLong() and 0xFFFFFFFFL)
        if (packed == lastLoggedColors) return
        lastLoggedColors = packed
        logd(TAG, "source=$source spec=2025 variant=$variantId platform=Phone dark=$dark sourceColor=${format(sourceColor)} fg=${format(foreground)} bg=${format(background)}")
    }

    private fun logWallpaperSnapshot(context: Context, phase: String) {
        if (!LogcatLogger.isDebugEnabled) return
        readFrameworkWallpaperColors(context)?.let { logWallpaper(it, "android_wallpaper_manager phase=$phase") }
            ?: logd(TAG, "wallpaper_colors phase=$phase source=android_wallpaper_manager value=null")
    }

    private fun logWallpaper(colors: WallpaperColors, source: String) {
        if (!LogcatLogger.isDebugEnabled) return
        val snapshot = "$source primary=${formatNullable(colors.primaryColor.toArgb())} secondary=${formatNullable(colors.secondaryColor?.toArgb())} tertiary=${formatNullable(colors.tertiaryColor?.toArgb())} hints=${colors.colorHints}"
        if (snapshot == lastLoggedWallpaper) return
        lastLoggedWallpaper = snapshot
        logd(TAG, "wallpaper_colors $snapshot")
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun format(color: Int): String = "0x${color.toUInt().toString(16).uppercase().padStart(8, '0')}"
    private fun formatNullable(color: Int?): String = color?.let(::format) ?: "null"

    private fun fallbackPalette(seed: Int): MonetPalette = MonetPalette(
        TonePalette.generate(seed), TonePalette.generate(seed), TonePalette.generate(seed),
        TonePalette.generate(seed), TonePalette.generate(seed),
    )
}
