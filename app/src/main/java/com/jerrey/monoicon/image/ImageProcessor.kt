package com.jerrey.monoicon.image

import android.graphics.Bitmap
import com.jerrey.monoicon.core.Result

/**
 * Pure image processor: converts a colored app icon [Bitmap]
 * into a monochrome [Bitmap].
 *
 * ## Purity Contract
 * This interface MUST NOT depend on:
 * - [com.jerrey.monoicon.cache.BitmapCache]
 * - [com.jerrey.monoicon.theme.ThemeProvider] / [com.jerrey.monoicon.theme.MonoTheme]
 * - [com.jerrey.monoicon.launcher.LauncherAdapter]
 * - [com.jerrey.monoicon.hook.IconThemeHook]
 * - [com.jerrey.monoicon.config.IconConfig]
 *
 * It is a **pure function**: [Bitmap] → [Bitmap].
 * Caching, theme application, and launcher integration are handled
 * by the orchestration layer ([com.jerrey.monoicon.service.ImageService]).
 *
 * ## Failure Behavior
 * If processing fails at any stage, the implementation must return
 * [Result.Error] containing the original bitmap so the launcher
 * can fall back to the unmodified icon.
 */
interface ImageProcessor {

    /**
     * Converts a colored app icon bitmap to monochrome.
     *
     * @param input The original colored icon bitmap (must not be recycled).
     * @return [Result.Success] with the monochrome bitmap,
     *         or [Result.Error] with the original bitmap on failure.
     */
    suspend fun process(input: Bitmap): Result<Bitmap>
}
