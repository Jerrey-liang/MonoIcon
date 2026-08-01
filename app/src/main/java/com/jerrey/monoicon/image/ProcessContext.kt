package com.jerrey.monoicon.image

import android.graphics.Bitmap

/**
 * Mutable context object that flows through the image processing pipeline.
 *
 * Each [ProcessingStage] receives this context, may modify
 * [outputBitmap] or [metadata], and returns it (or a failure).
 *
 * @param inputBitmap The original bitmap passed into the pipeline. Never modified.
 * @param outputBitmap The working bitmap. Stages read from and write to this.
 *                      Initially `null` — the first stage typically copies [inputBitmap].
 * @param metadata Arbitrary key-value store for inter-stage communication
 *                  (e.g., "edge_map", "foreground_mask", "luminance_stats").
 */
data class ProcessContext(
    val inputBitmap: Bitmap,
    var outputBitmap: Bitmap? = null,
    val metadata: MutableMap<String, Any> = mutableMapOf()
) {
    /**
     * When set to `true`, remaining stages in the pipeline will be skipped.
     * Use this when a stage determines that processing should abort early
     * (e.g., the icon is already suitable, or an unrecoverable issue is detected).
     */
    var skipRemaining: Boolean = false

    /**
     * Convenience: returns [outputBitmap] if set, otherwise [inputBitmap].
     */
    fun currentBitmap(): Bitmap = outputBitmap ?: inputBitmap
}
