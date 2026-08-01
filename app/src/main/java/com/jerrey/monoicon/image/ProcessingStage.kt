package com.jerrey.monoicon.image

import com.jerrey.monoicon.core.Result

/**
 * A single, independently replaceable step in the monochrome
 * icon generation pipeline.
 *
 * ## Contract
 * - Receives a [ProcessContext] with the current state.
 * - Returns [Result.Success] with an updated context, or
 *   [Result.Error] if this stage cannot complete.
 * - Must NOT throw exceptions — wrap all failures in [Result.Error].
 * - Must NOT modify [ProcessContext.inputBitmap].
 * - May read/write [ProcessContext.outputBitmap] and [ProcessContext.metadata].
 *
 * ## Threading
 * [process] is a `suspend` function. Stages may perform heavy
 * pixel-level work and should be executed on a background dispatcher
 * by the pipeline orchestrator.
 *
 * ## Naming
 * [name] is used for logging and performance tracing. Choose a short,
 * descriptive name (e.g., "ForegroundExtraction", "EdgeDetection").
 */
interface ProcessingStage {

    /** Human-readable name for logging and performance tracing. */
    val name: String

    /**
     * Processes the current pipeline context.
     *
     * @param context The current state of the pipeline.
     * @return Updated context on success, or an error describing the failure.
     */
    suspend fun process(context: ProcessContext): Result<ProcessContext>
}
