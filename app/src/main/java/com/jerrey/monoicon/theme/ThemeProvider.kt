package com.jerrey.monoicon.theme

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import com.jerrey.monoicon.core.Result
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logw

private const val TAG = "MonoIcon.Theme"

/**
 * Provides the current theme colors for monochrome icon tinting.
 *
 * Implementations may source colors from:
 * - System wallpaper (Material You / Monet) via [WallpaperColors] API (31+)
 * - User-defined custom colors
 * - Static fallback palettes
 */
interface ThemeProvider {

    /**
     * Returns the currently active [MonoTheme].
     *
     * This is a [suspend] function because color extraction may involve
     * disk I/O (reading wallpaper) or other slow operations.
     */
    suspend fun getCurrentTheme(): Result<MonoTheme>

    companion object {
        /**
         * Creates a [ThemeProvider] that reads colors from the system wallpaper.
         * Available on API 31+ (Android 12). Returns [FallbackThemeProvider]
         * on older API levels.
         */
        fun fromContext(context: Context): ThemeProvider = SystemThemeProvider(context)

        /**
         * Creates a [ThemeProvider] that always returns a static fallback theme.
         * Useful for testing or when wallpaper access is unavailable.
         */
        fun fallback(): ThemeProvider = FallbackThemeProvider()
    }
}

/**
 * [ThemeProvider] that extracts colors from the system wallpaper
 * via [WallpaperManager.getWallpaperColors].
 */
internal class SystemThemeProvider(
    private val context: Context
) : ThemeProvider {

    override suspend fun getCurrentTheme(): Result<MonoTheme> {
        return Result.of {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                logw(TAG, "WallpaperColors API not available (API < 31), using fallback")
                return@of MonoTheme.FALLBACK
            }

            val wallpaperManager = WallpaperManager.getInstance(context)
            val colors: WallpaperColors? = wallpaperManager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)

            if (colors == null) {
                logw(TAG, "WallpaperColors returned null, using fallback")
                return@of MonoTheme.FALLBACK
            }

            mapWallpaperColors(colors)
        }
    }

    private fun mapWallpaperColors(colors: WallpaperColors): MonoTheme {
        val primary = colors.primaryColor?.toArgb() ?: MonoTheme.FALLBACK.primaryColor
        val secondary = colors.secondaryColor?.toArgb() ?: primary
        val tertiary = colors.tertiaryColor?.toArgb() ?: secondary
        val isDark = isCurrentThemeDark(context)

        return MonoTheme(
            primaryColor = primary,
            surfaceColor = if (isDark) 0xFF1C1B1F.toInt() else 0xFFFFFBFE.toInt(),
            onSurfaceColor = if (isDark) 0xFFE6E1E5.toInt() else 0xFF1C1B1F.toInt(),
            isDarkMode = isDark,
            adaptiveColors = listOf(primary, secondary, tertiary)
        )
    }

    private fun isCurrentThemeDark(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}

/**
 * [ThemeProvider] that always returns a static fallback theme.
 */
internal class FallbackThemeProvider : ThemeProvider {
    override suspend fun getCurrentTheme(): Result<MonoTheme> {
        return Result.Success(MonoTheme.FALLBACK)
    }
}