package com.jerrey.monoicon.theme.color.dynamic

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.jerrey.monoicon.logging.LogcatLogger
import com.jerrey.monoicon.logging.logd

/**
 * Reads the final dynamic colors already resolved by the HyperOS Launcher.
 *
 * Launcher 2300 exposes the following resource chain:
 *
 * `com.miui.home:material_dynamic_primary40/95/80/10`
 *   -> `m3_ref_palette_dynamic_primary*`
 *   -> `android:system_accent1_*`
 *
 * Those resources are populated by the system's Monet/RRO pipeline, so this
 * class deliberately does not select wallpaper seeds or reimplement Monet.
 * The RGB values are read on every call; only resource IDs are cached.
 */
object PixelMonetColorEngine {

    private const val TAG = "MonoIcon.Monet"

    internal const val PRIMARY_LIGHT_FOREGROUND = "material_dynamic_primary40"
    internal const val PRIMARY_LIGHT_BACKGROUND = "material_dynamic_primary95"
    internal const val PRIMARY_DARK_FOREGROUND = "material_dynamic_primary80"
    internal const val PRIMARY_DARK_BACKGROUND = "material_dynamic_primary10"

    private const val SYSTEM_LIGHT_FOREGROUND = android.R.color.system_accent1_600
    private const val SYSTEM_LIGHT_BACKGROUND = android.R.color.system_accent1_50
    private const val SYSTEM_DARK_FOREGROUND = android.R.color.system_accent1_200
    private const val SYSTEM_DARK_BACKGROUND = android.R.color.system_accent1_900

    private val COMPATIBILITY_PALETTE = fallbackPalette(0xFF4A6D8C.toInt())

    private val LIGHT_FALLBACK = IconThemeColors(
        foreground = 0xFF3C4043.toInt(),
        background = 0xFFF5F5F5.toInt(),
        palette = COMPATIBILITY_PALETTE,
    )
    private val DARK_FALLBACK = IconThemeColors(
        foreground = 0xFFDADCE0.toInt(),
        background = 0xFF202124.toInt(),
        palette = COMPATIBILITY_PALETTE,
    )

    @Volatile
    private var launcherContext: Context? = null

    @Volatile
    private var launcherLightForegroundId = 0

    @Volatile
    private var launcherLightBackgroundId = 0

    @Volatile
    private var launcherDarkForegroundId = 0

    @Volatile
    private var launcherDarkBackgroundId = 0

    @Volatile
    private var lastLoggedColors: Long = Long.MIN_VALUE

    /** Binds the Launcher process context and resolves resource IDs once. */
    fun init(context: Context) {
        val appContext = context.applicationContext
        launcherContext = appContext
        resolveLauncherResourceIds(appContext.resources, appContext.packageName)
        logd(TAG, "initialized package=${appContext.packageName}")
    }

    /** Re-resolves IDs and permits a fresh diagnostic log on the next read. */
    fun invalidate() {
        val context = launcherContext ?: return
        resolveLauncherResourceIds(context.resources, context.packageName)
        lastLoggedColors = Long.MIN_VALUE
        logd(TAG, "invalidated")
    }

    /** Reads current overlay RGB values on every invocation. */
    fun getIconColors(): IconThemeColors {
        val context = launcherContext ?: return LIGHT_FALLBACK
        val dark = isSystemDark(context)

        val launcherPair = readLauncherOverlay(context, dark)
        if (launcherPair != null) {
            logColors("launcher_overlay", dark, launcherPair.first, launcherPair.second)
            return colorsFromPair(launcherPair)
        }

        val systemPair = readSystemAccent(context, dark)
        if (systemPair != null) {
            logColors("system_accent_fallback", dark, systemPair.first, systemPair.second)
            return colorsFromPair(systemPair)
        }

        return if (dark) DARK_FALLBACK else LIGHT_FALLBACK
    }

    private fun resolveLauncherResourceIds(resources: Resources, packageName: String) {
        launcherLightForegroundId = resources.getIdentifier(
            PRIMARY_LIGHT_FOREGROUND, "color", packageName
        )
        launcherLightBackgroundId = resources.getIdentifier(
            PRIMARY_LIGHT_BACKGROUND, "color", packageName
        )
        launcherDarkForegroundId = resources.getIdentifier(
            PRIMARY_DARK_FOREGROUND, "color", packageName
        )
        launcherDarkBackgroundId = resources.getIdentifier(
            PRIMARY_DARK_BACKGROUND, "color", packageName
        )
    }

    private fun readLauncherOverlay(context: Context, dark: Boolean): Pair<Int, Int>? {
        val foregroundId = if (dark) launcherDarkForegroundId else launcherLightForegroundId
        val backgroundId = if (dark) launcherDarkBackgroundId else launcherLightBackgroundId
        if (foregroundId == 0 || backgroundId == 0) return null
        return try {
            val foreground = context.getColor(foregroundId)
            val background = context.getColor(backgroundId)
            if (isVisible(foreground) && isVisible(background)) foreground to background else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun readSystemAccent(context: Context, dark: Boolean): Pair<Int, Int>? {
        return try {
            val foreground = context.getColor(
                if (dark) SYSTEM_DARK_FOREGROUND else SYSTEM_LIGHT_FOREGROUND
            )
            val background = context.getColor(
                if (dark) SYSTEM_DARK_BACKGROUND else SYSTEM_LIGHT_BACKGROUND
            )
            if (isVisible(foreground) && isVisible(background)) foreground to background else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun isVisible(color: Int): Boolean = (color ushr 24) != 0

    private fun logColors(source: String, dark: Boolean, foreground: Int, background: Int) {
        if (!LogcatLogger.isDebugEnabled) return
        val packed = (foreground.toLong() shl 32) xor (background.toLong() and 0xFFFFFFFFL)
        if (packed == lastLoggedColors) return
        lastLoggedColors = packed
        logd(
            TAG,
            "source=$source dark=$dark " +
                "fg=0x${foreground.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                "bg=0x${background.toUInt().toString(16).uppercase().padStart(8, '0')}",
        )
    }

    private fun colorsFromPair(pair: Pair<Int, Int>): IconThemeColors = IconThemeColors(
        foreground = pair.first,
        background = pair.second,
        // Kept only for API compatibility; icon rendering uses the pair above.
        palette = COMPATIBILITY_PALETTE,
    )

    private fun fallbackPalette(seed: Int): MonetPalette = MonetPalette(
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
    )
}
