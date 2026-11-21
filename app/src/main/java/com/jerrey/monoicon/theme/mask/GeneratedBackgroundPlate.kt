package com.jerrey.monoicon.theme.mask

import kotlin.math.abs

/** Conservative geometry classification; no source pixels are removed or recolored. */
internal object GeneratedBackgroundPlate {
    private const val EDGE_MIN_ALPHA = 224
    private const val INTERIOR_MIN_ALPHA = 250
    private const val MAX_NEIGHBOR_CHANGE = 8
    private const val MAX_EDGE_CHANGE = 16
    private const val MAX_SMOOTH_CURVATURE = 4

    /**
     * A nearly opaque, smoothly varying field is a plate for border measurement.
     * Linear and radial gradients qualify; alpha structure, distinct color edges
     * and repeated fine texture keep the composed artwork's own geometry.
     *
     * Rasterizing a full layer can disturb its outermost premultiplied pixels.
     * Allow that single edge row a little more rounding error, while inspecting
     * every pixel so even a small background-layer logo is not sampled away.
     */
    fun isSmoothPlate(pixels: IntArray, width: Int, height: Int): Boolean {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val inset = if (width >= 8 && height >= 8) 1 else 0
        fun interior(x: Int, y: Int): Boolean =
            x >= inset && y >= inset && x < width - inset && y < height - inset
        var roughComparisons = 0
        var curvatureComparisons = 0
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            val pixel = pixels[i]
            val isInterior = interior(x, y)
            val minimumAlpha = if (isInterior) INTERIOR_MIN_ALPHA else EDGE_MIN_ALPHA
            if (pixel ushr 24 < minimumAlpha) return false
            fun checkNeighbor(nextX: Int, nextY: Int): Boolean {
                val limit = if (isInterior && interior(nextX, nextY)) MAX_NEIGHBOR_CHANGE else MAX_EDGE_CHANGE
                return maxChannelDifference(pixel, pixels[nextY * width + nextX]) <= limit
            }
            if (x + 1 < width && !checkNeighbor(x + 1, y)) return false
            if (y + 1 < height && !checkNeighbor(x, y + 1)) return false
            if (isInterior && x + 2 < width - inset) {
                curvatureComparisons++
                if (maxChannelCurvature(pixel, pixels[i + 1], pixels[i + 2]) > MAX_SMOOTH_CURVATURE) roughComparisons++
            }
            if (isInterior && y + 2 < height - inset) {
                curvatureComparisons++
                if (maxChannelCurvature(pixel, pixels[i + width], pixels[i + width * 2]) > MAX_SMOOTH_CURVATURE) roughComparisons++
            }
        }
        // Sparse rounding exceptions are possible; texture creates repeated
        // reversals throughout the field. Small images permit no exceptions.
        return roughComparisons <= curvatureComparisons / 100
    }

    private fun maxChannelDifference(first: Int, second: Int): Int = maxOf(
        abs((first ushr 16 and 255) - (second ushr 16 and 255)),
        abs((first ushr 8 and 255) - (second ushr 8 and 255)),
        abs((first and 255) - (second and 255)),
    )

    private fun maxChannelCurvature(first: Int, middle: Int, last: Int): Int = maxOf(
        abs((first ushr 16 and 255) - 2 * (middle ushr 16 and 255) + (last ushr 16 and 255)),
        abs((first ushr 8 and 255) - 2 * (middle ushr 8 and 255) + (last ushr 8 and 255)),
        abs((first and 255) - 2 * (middle and 255) + (last and 255)),
    )
}
