package com.jerrey.monoicon.theme.render

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rounds the artwork's occupied rectangle, independently of the surrounding icon plate. */
internal class GeneratedArtworkShape private constructor(
    private val alpha: ByteArray,
    private val width: Int,
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
) {
    /** Retains authored offsets, holes and tones; only corner coverage is removed. */
    fun roundedAlpha(radiusFraction: Float): ByteArray {
        val fraction = normalizeRadiusFraction(radiusFraction)
        val result = alpha.copyOf()
        if (fraction == 0f) return result
        val radius = minOf(right - left, bottom - top) * fraction
        val centerX = (left + right) / 2f
        val centerY = (top + bottom) / 2f
        val innerHalfWidth = (right - left) / 2f - radius
        val innerHalfHeight = (bottom - top) / 2f - radius
        for (y in top until bottom) for (x in left until right) {
            val index = y * width + x
            val original = alpha[index].toInt() and 255
            if (original == 0) continue
            val dx = maxOf(abs(x + 0.5f - centerX) - innerHalfWidth, 0f)
            val dy = maxOf(abs(y + 0.5f - centerY) - innerHalfHeight, 0f)
            val distance = sqrt(dx * dx + dy * dy)
            if (distance <= radius - PIXEL_HALF_DIAGONAL) continue
            if (distance >= radius + PIXEL_HALF_DIAGONAL) {
                result[index] = 0
                continue
            }
            // Supersample only edge cells. Interior coverage is byte-identical.
            var covered = 0
            for (sampleY in 0 until SAMPLES) for (sampleX in 0 until SAMPLES) {
                val sx = x + (sampleX + 0.5f) / SAMPLES
                val sy = y + (sampleY + 0.5f) / SAMPLES
                val cornerX = maxOf(abs(sx - centerX) - innerHalfWidth, 0f)
                val cornerY = maxOf(abs(sy - centerY) - innerHalfHeight, 0f)
                if (cornerX * cornerX + cornerY * cornerY <= radius * radius) covered++
            }
            result[index] = (original * covered / (SAMPLES * SAMPLES).toFloat()).roundToInt().toByte()
        }
        return result
    }

    companion object {
        private const val SAMPLES = 4
        private const val PIXEL_HALF_DIAGONAL = 0.707107f

        fun normalizeRadiusFraction(value: Float): Float =
            if (value.isFinite()) value.coerceIn(0f, 0.5f) else 0f

        /** Transparent bitmap padding is excluded from the adjustable pattern. */
        fun fromAlpha(alpha: ByteArray, width: Int, height: Int): GeneratedArtworkShape? {
            require(width > 0 && height > 0 && alpha.size == width * height)
            var left = width
            var top = height
            var right = 0
            var bottom = 0
            for (y in 0 until height) for (x in 0 until width) {
                if (alpha[y * width + x].toInt() and 255 == 0) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
            return if (right <= left || bottom <= top) null
            else GeneratedArtworkShape(alpha.copyOf(), width, left, top, right, bottom)
        }
    }
}
