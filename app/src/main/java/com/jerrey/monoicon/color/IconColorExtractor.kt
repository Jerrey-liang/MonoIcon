package com.jerrey.monoicon.color

import android.graphics.Bitmap

/**
 * Extracts a representative color from an icon [Bitmap].
 *
 * Implementations MUST NOT modify the input bitmap or produce side effects.
 * The returned color is an ARGB [Int] in the sRGB color space,
 * as returned by [android.graphics.Color].
 *
 * ## Contract
 * - Pure function: [Bitmap] → ARGB [Int].
 * - Thread-safe: may be called from any thread.
 * - Never throws: failures return [FALLBACK_COLOR] (0xFF000000, opaque black).
 * - No I/O, no allocations beyond local variables in the hot path.
 *
 * ## Design
 * This interface is deliberately minimal. Multi-color palette extraction
 * or confidence scoring can be added as separate interfaces later,
 * keeping the single-color extraction path fast and predictable.
 */
interface IconColorExtractor {

    /**
     * Extracts the most visually representative color from [bitmap].
     *
     * @param bitmap The rendered icon bitmap (ARGB_8888 or similar).
     *               Must not be recycled. Transparent regions are skipped.
     * @return An ARGB color int representing the dominant icon color,
     *         or [FALLBACK_COLOR] if extraction fails.
     */
    fun extractDominantColor(bitmap: Bitmap): Int

    companion object {
        /** Opaque black — returned when extraction cannot produce a valid result. */
        const val FALLBACK_COLOR: Int = 0xFF000000.toInt()
    }
}
