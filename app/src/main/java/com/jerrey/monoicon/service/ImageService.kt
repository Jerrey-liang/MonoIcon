package com.jerrey.monoicon.service

import android.graphics.Bitmap
import com.jerrey.monoicon.cache.BitmapCache
import com.jerrey.monoicon.core.Result
import com.jerrey.monoicon.image.ImageProcessor
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.theme.MonoTheme

private const val TAG = "MonoIcon.Service"

/**
 * Orchestrates the full icon processing flow:
 *
 * ```
 * Cache lookup → ImageProcessor (pure) → Theme coloring → Cache store → Result
 * ```
 *
 * This is the **single entry point** called by the hook layer.
 * It coordinates cache, image processing, and theme application,
 * keeping each component independent of the others.
 *
 * ## Cache Key
 * The cache key is composed of: `packageName + theme primary color + isDarkMode`.
 * This ensures cached icons are invalidated when the theme changes.
 */
interface ImageService {

    /**
     * Process an app icon through the full pipeline with caching.
     *
     * @param packageName The package name of the app whose icon is being processed.
     * @param iconBitmap The original colored icon bitmap extracted by the launcher adapter.
     * @param theme The current theme colors for tinting.
     * @return The final tinted monochrome bitmap, or the original on failure.
     */
    suspend fun process(
        packageName: String,
        iconBitmap: Bitmap,
        theme: MonoTheme
    ): Result<Bitmap>
}

/**
 * Default implementation of [ImageService].
 *
 * Orchestration flow:
 * 1. Build cache key from package + theme
 * 2. Check cache → return if hit
 * 3. Process bitmap through [ImageProcessor] → pure monochrome
 * 4. Apply theme colors to monochrome bitmap
 * 5. Store in cache
 * 6. Return final bitmap
 *
 * @param cache The bitmap cache for storing processed results.
 * @param imageProcessor The pure image processor (Bitmap → monochrome Bitmap).
 */
internal class DefaultImageService(
    private val cache: BitmapCache,
    private val imageProcessor: ImageProcessor
) : ImageService {

    override suspend fun process(
        packageName: String,
        iconBitmap: Bitmap,
        theme: MonoTheme
    ): Result<Bitmap> {
        val cacheKey = buildCacheKey(packageName, theme)
        logd(TAG, "Processing icon for: $packageName (key=$cacheKey)")

        // 1. Check cache
        val cached = cache.get(cacheKey)
        if (cached != null) {
            logi(TAG, "Returning cached icon for: $packageName")
            return Result.Success(cached)
        }

        // 2. Pure image processing (Bitmap → monochrome Bitmap)
        val monochromeResult = imageProcessor.process(iconBitmap)
        if (monochromeResult.isError) {
            loge(TAG, "Image processing failed for: $packageName, returning original")
            return Result.Success(iconBitmap) // Fail-safe: return original
        }

        val monochromeBitmap = monochromeResult.getOrThrow()

        // 3. Apply theme colors
        val themedResult = applyTheme(monochromeBitmap, theme)
        if (themedResult.isError) {
            loge(TAG, "Theme application failed for: $packageName, returning monochrome")
            return Result.Success(monochromeBitmap) // Return uncolored monochrome
        }

        val finalBitmap = themedResult.getOrThrow()

        // 4. Store in cache
        cache.put(cacheKey, finalBitmap)

        logi(TAG, "Successfully processed icon for: $packageName")
        return Result.Success(finalBitmap)
    }

    /**
     * Applies the theme colors to a monochrome bitmap.
     *
     * In Phase 1, this is a placeholder that returns the monochrome bitmap unchanged.
     * The actual tinting will be implemented when the theme engine is built.
     */
    private fun applyTheme(monochromeBitmap: Bitmap, theme: MonoTheme): Result<Bitmap> {
        // TODO: Implement theme color application
        // For Phase 1, return the monochrome bitmap as-is.
        // The theming will:
        // 1. Use theme.foregroundColor to tint non-transparent pixels
        // 2. Use theme.surfaceColor for background areas
        // 3. Support multi-tone via theme.adaptiveColors (future)
        logd(TAG, "Theme application (placeholder): foregroundColor=${theme.foregroundColor}")
        return Result.Success(monochromeBitmap)
    }

    /**
     * Builds a cache key from the package name and current theme.
     *
     * The key format is: `{packageName}|{primaryColor}|{darkMode}`
     * This ensures that theme changes automatically invalidate the cache.
     */
    private fun buildCacheKey(packageName: String, theme: MonoTheme): String {
        return "$packageName|${theme.primaryColor}|${theme.isDarkMode}"
    }
}
