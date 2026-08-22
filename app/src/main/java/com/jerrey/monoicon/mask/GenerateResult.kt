package com.jerrey.monoicon.mask

import android.graphics.Bitmap

/**
 * Result of a mask-generation strategy (Phase 6.6: moved out of the
 * legacy MaskGenerator, which has been removed — only the Pixel
 * Launcher pipeline remains).
 */
data class GenerateResult(
    val mask: Bitmap?,
    val source: Int,
    val rawUsed: Boolean,
    val cacheHit: Boolean,
)
