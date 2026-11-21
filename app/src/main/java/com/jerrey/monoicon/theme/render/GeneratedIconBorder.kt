package com.jerrey.monoicon.theme.render

import kotlin.math.abs
import kotlin.math.sqrt

/** Uniform artwork zoom inside a fixed outer plate; source pixels are never edited. */
internal class GeneratedIconBorder private constructor(
    private val outline: FloatArray,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val defaultExtent: Float,
) {
    private data class Geometry(val width: Float, val height: Float, val radius: Float)
    private var lastGeometry: Geometry? = null
    private var lastFitScale = 1f
    private var lastDefaultScale = 1f

    /** 100% uses a common exterior margin; 0% fits the complete authored artwork. */
    fun glyphScale(width: Float, height: Float, radius: Float, ratio: Float): Float {
        require(width > 0f && height > 0f && width.isFinite() && height.isFinite() && radius.isFinite())
        val amount = normalizeRatio(ratio)
        val geometry = Geometry(width, height, radius.coerceIn(0f, minOf(width, height) / 2f))
        if (lastGeometry != geometry) {
            val baseScale = minOf(width / sourceWidth, height / sourceHeight)
            lastFitScale = fitArtwork(geometry)
            // Keep each app's authored center and proportions. The common 100%
            // margin must also respect faint edges outside the visible extent.
            lastDefaultScale = minOf(lastFitScale,
                DEFAULT_ARTWORK_FRACTION * minOf(width, height) / (2f * defaultExtent * baseScale))
            lastGeometry = geometry
        }
        return lastDefaultScale * amount + lastFitScale * (1f - amount)
    }

    /**
     * The first occupied pixel to touch the rounded plate limits uniform zoom.
     * This also applies to dense plates and closed frames: covering the whole
     * plate would cut their corners off. A wide tile must not stretch the glyph
     * or enlarge it past the short sides just to fill its long axis.
     */
    private fun fitArtwork(geometry: Geometry): Float {
        val (width, height, radius) = geometry
        val baseScale = minOf(width / sourceWidth, height / sourceHeight)
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        var fit = Float.POSITIVE_INFINITY
        for (i in outline.indices step 2) {
            val dx = abs(outline[i] - sourceWidth / 2f) * baseScale
            val dy = abs(outline[i + 1] - sourceHeight / 2f) * baseScale
            var high = minOf(if (dx > 0f) halfWidth / dx else Float.POSITIVE_INFINITY,
                if (dy > 0f) halfHeight / dy else Float.POSITIVE_INFINITY)
            if (!high.isFinite()) continue
            if (radius > 0f) {
                var low = 0f
                repeat(18) {
                    val scale = (low + high) / 2f
                    val x = maxOf(dx * scale - (halfWidth - radius), 0f)
                    val y = maxOf(dy * scale - (halfHeight - radius), 0f)
                    if (x * x + y * y <= radius * radius) low = scale else high = scale
                }
                high = low
            }
            fit = minOf(fit, high)
        }
        // Some full-bleed or offset sources already cross a rounded corner at
        // scale 1. Allow shrinking them so the original pattern stays intact.
        return if (fit.isFinite()) fit else 1f
    }

    companion object {
        // AOSP's 0.7 legacy inset times the solid-square normalizer scale.
        private val DEFAULT_ARTWORK_FRACTION = 0.7f * sqrt(375f / 576f)

        fun normalizeRatio(ratio: Float): Float = if (ratio.isFinite()) ratio.coerceIn(0f, 1f) else 1f

        /** Each source's real alpha footprint determines its no-border size. */
        fun fromAlpha(alpha: ByteArray, width: Int, height: Int): GeneratedIconBorder? {
            require(width > 0 && height > 0 && alpha.size == width * height)
            val maximumAlpha = alpha.maxOf { it.toInt() and 255 }
            if (maximumAlpha == 0) return null
            val threshold = minOf(32, maximumAlpha)
            var visibleLeft = width
            var visibleTop = height
            var visibleRight = -1
            var visibleBottom = -1
            // A rounded rectangle is convex. Each row's left/right occupied
            // pixel-cell corners bound all artwork, including disconnected
            // strokes and enclosed holes, without filling or editing its alpha.
            val outline = FloatArray(height * 8)
            var size = 0
            for (y in 0 until height) {
                var left = width
                var right = -1
                for (x in 0 until width) {
                    val coverage = alpha[y * width + x].toInt() and 255
                    if (coverage == 0) continue
                    left = minOf(left, x)
                    right = x
                    if (coverage >= threshold) {
                        visibleLeft = minOf(visibleLeft, x)
                        visibleRight = maxOf(visibleRight, x)
                        visibleTop = minOf(visibleTop, y)
                        visibleBottom = maxOf(visibleBottom, y)
                    }
                }
                if (right < left) continue
                for (edgeY in y..y + 1) {
                    outline[size++] = left.toFloat()
                    outline[size++] = edgeY.toFloat()
                    outline[size++] = (right + 1).toFloat()
                    outline[size++] = edgeY.toFloat()
                }
            }
            val defaultExtent = maxOf(width / 2f - visibleLeft, visibleRight + 1f - width / 2f,
                height / 2f - visibleTop, visibleBottom + 1f - height / 2f)
            return GeneratedIconBorder(outline.copyOf(size), width, height, defaultExtent)
        }
    }
}
