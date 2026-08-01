package com.jerrey.monoicon.image

import android.graphics.Bitmap
import com.jerrey.monoicon.core.Result

/**
 * Orchestrates a sequence of [ProcessingStage] instances to convert
 * a colored icon bitmap into a monochrome bitmap.
 *
 * ## Execution
 * Stages are executed in insertion order. If any stage returns
 * [Result.Error], the pipeline stops and returns the original [input]
 * bitmap (fail-safe behavior — the launcher will display the original icon).
 *
 * If a stage sets [ProcessContext.skipRemaining] to `true`, remaining
 * stages are skipped and the pipeline returns the current output.
 *
 * ## Thread Safety
 * The [process] method is a `suspend` function meant to be called from
 * a background dispatcher. The stage list is NOT thread-safe — add or
 * remove stages only from a single initialization thread before processing
 * begins.
 */
interface ImagePipeline {

    /**
     * Runs all stages sequentially on [input].
     *
     * @param input The original colored app icon bitmap.
     * @return [Result.Success] with the final monochrome bitmap,
     *         or [Result.Error] with the original [input] if processing failed.
     */
    suspend fun process(input: Bitmap): Result<Bitmap>

    /**
     * Appends a stage to the end of the pipeline.
     * Stages are executed in the order they were added.
     */
    fun addStage(stage: ProcessingStage)

    /**
     * Removes the first stage whose [ProcessingStage.name] matches [name].
     * Idempotent — does nothing if no matching stage is found.
     */
    fun removeStage(name: String)

    /**
     * Returns the current number of stages in the pipeline.
     */
    val stageCount: Int
}
