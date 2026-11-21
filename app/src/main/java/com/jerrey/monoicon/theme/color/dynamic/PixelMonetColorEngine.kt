package com.jerrey.monoicon.theme.color.dynamic

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.theme.wallpaper.WallpaperSnapshot
import com.jerrey.monoicon.theme.wallpaper.WallpaperSourceRepository

/** Runtime adapter: the selected Material specification always uses our image-derived seed. */
object PixelMonetColorEngine {
    private const val TAG = "MonoIcon.Monet"
    private data class CachedIconColors(val request: IconColorRequest, val colors: IconThemeColors)
    @Volatile private var cachedIconColors: CachedIconColors? = null
    @Volatile private var launcherContext: Context? = null
    @Volatile private var lastLoggedColors: Long = Long.MIN_VALUE

    fun init(context: Context) {
        launcherContext = context.applicationContext ?: context
        WallpaperSourceRepository.init(context)
    }

    /** Keep the usable image during configuration and wallpaper invalidation. */
    fun invalidate() {
        lastLoggedColors = Long.MIN_VALUE
        cachedIconColors = null
        WallpaperSourceRepository.invalidate()
    }

    fun getIconColors(): IconThemeColors {
        val context = launcherContext
        // Existing loader/publisher workers await retrieval; icon drawing and Compose never do.
        val wallpaper = if (context != null && Looper.myLooper() != Looper.getMainLooper()) {
            WallpaperSourceRepository.refreshAndAwait(context)
        } else WallpaperSourceRepository.state.value
        return colorsForSnapshot(wallpaper, context?.let(::isSystemDark) ?: false)
    }

    /** Raw image seed, also used by the settings ThemeController without re-seeding from a role. */
    fun getSeedColor(): Int = WallpaperSourceRepository.state.value.seedColor

    private fun colorsForSnapshot(wallpaper: WallpaperSnapshot, dark: Boolean): IconThemeColors {
        val sourceColor = wallpaper.seedColor
        val variantId = ConfigManager.getVariantId()
        val engine = IconColorEngineId.fromId(ConfigManager.getColorEngineId())
        val request = IconColorRequest(sourceColor, dark, Material2025ColorEngine.variantFromId(variantId), engine)
        val cached = cachedIconColors
        val result = if (cached?.request == request) cached.colors else {
            MaterialIconColorEngine.iconColorsForSource(sourceColor, dark, request.variant, engine)
                .also { cachedIconColors = CachedIconColors(request, it) }
        }
        logColors(wallpaper.provenance.name, dark, variantId, engine, sourceColor, result.foreground, result.background)
        return result
    }

    /** Bounded Celebi + Score extraction. The caller retains ownership of its bitmap. */
    fun seedFromBitmap(bitmap: Bitmap): Int? = try {
        WallpaperSourceRepository.seedFromBitmap(bitmap)
    } catch (_: Exception) {
        null
    }

    private fun logColors(source: String, dark: Boolean, variantId: String, engine: IconColorEngineId, sourceColor: Int, foreground: Int, background: Int) {
        if (!LogcatLogger.isDebugEnabled) return
        val packed = (foreground.toLong() shl 32) xor (background.toLong() and 0xFFFFFFFFL)
        if (packed == lastLoggedColors) return
        lastLoggedColors = packed
        logd(TAG, "source=$source engine=${engine.id} spec=${engine.specVersion} variant=$variantId platform=Phone dark=$dark sourceColor=${format(sourceColor)} fg=${format(foreground)} bg=${format(background)}")
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun format(color: Int): String = "0x${color.toUInt().toString(16).uppercase().padStart(8, '0')}"
}
