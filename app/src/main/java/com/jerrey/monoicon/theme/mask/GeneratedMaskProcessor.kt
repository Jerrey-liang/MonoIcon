package com.jerrey.monoicon.theme.mask

import kotlin.math.roundToInt

/**
 * Pixel-only finishing for automatically generated masks. RGB describes artwork;
 * alpha describes coverage. In particular, an opaque white pixel is never a hole.
 * Author-provided monochrome masks must not pass through this processor.
 */
internal object GeneratedMaskProcessor {
    private const val VISIBLE_ALPHA = 32
    private const val DETAIL_ALPHA = 112
    private const val PLATE_ALPHA = 80
    private const val COLOR_TOLERANCE_SQUARED = 32 * 32

    /**
     * Keeps source coverage in alpha and normalized Rec.709 tone in RGB.
     * A white field, enclosed white detail, or dark stroke is all artwork here;
     * only genuine source transparency creates a hole. Palette polarity belongs
     * to the renderer, not to extraction.
     *
     * Near-flat artwork uses white RGB without amplifying small color noise.
     * Callers can use [isSingleTone] to render that artwork as a tinted silhouette.
     */
    fun tonal(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val (low, high) = toneRange(pixels)
        val range = high - low
        val singleTone = isSingleTone(pixels)
        return IntArray(pixels.size) { i ->
            val coverage = alpha(pixels[i])
            if (coverage == 0) return@IntArray 0
            val tone = if (singleTone) 255 else {
                ((luminance(pixels[i]) - low).coerceIn(0, range) * 255 + range / 2) / range
            }
            (coverage shl 24) or (tone shl 16) or (tone shl 8) or tone
        }
    }

    /** Transparent RGB and premultiplied edge rounding do not invent tonal detail. */
    fun isSingleTone(pixels: IntArray): Boolean {
        val (low, high) = toneRange(pixels, accountForRounding = true)
        return high - low < 12
    }

    private fun toneRange(pixels: IntArray, accountForRounding: Boolean = false): Pair<Int, Int> {
        var low = 255
        var high = 0
        // Preserve intentional low-opacity artwork when no solid interior exists.
        val minimumAlpha = if (pixels.any { alpha(it) >= VISIBLE_ALPHA }) VISIBLE_ALPHA else 1
        for (pixel in pixels) {
            val coverage = alpha(pixel)
            if (coverage < minimumAlpha) continue
            val tone = luminance(pixel)
            // A raster edge can lose one premultiplied RGB byte relative to
            // coverage. Unpremultiplication expands that to ceil(255 / alpha)
            // tone levels. Compare the smallest range consistent with that
            // uncertainty; opaque pixels retain the exact 12-level threshold.
            val uncertainty = if (accountForRounding && coverage < 255) (255 + coverage - 1) / coverage else 0
            low = minOf(low, (tone + uncertainty).coerceAtMost(255))
            high = maxOf(high, (tone - uncertainty).coerceAtLeast(0))
        }
        return low to high
    }

    /** Rec.709 weights on source sRGB channels; exact for neutral gray pixels. */
    private fun luminance(pixel: Int): Int = (
        (pixel ushr 16 and 255) * 2126 + (pixel ushr 8 and 255) * 7152 +
            (pixel and 255) * 722 + 5000
        ) / 10000

    /**
     * [baseline] is the existing luminance mask and bounds the retained source
     * plate's tonal coverage. The rendered outer plate is controlled separately.
     * [pixels] must be the source foreground, before flattening onto a wrapper.
     * [borderRatio] remains for callers compiled against the old API; it never
     * changes source artwork or its alpha coverage.
     */
    @Suppress("UNUSED_PARAMETER")
    fun refine(
        pixels: IntArray,
        width: Int,
        height: Int,
        baseline: ByteArray,
        borderRatio: Float = 1f,
    ): ByteArray {
        require(width > 0 && height > 0 && pixels.size == width * height)
        require(baseline.size == pixels.size)
        val plate = detectExteriorPlate(pixels, width, height)
        var low = 255
        var high = 0
        for (i in pixels.indices) {
            if (alpha(pixels[i]) < VISIBLE_ALPHA || plate?.get(i) == true) continue
            val gray = gray(pixels[i])
            low = minOf(low, gray)
            high = maxOf(high, gray)
        }
        if (high < low) return ByteArray(pixels.size)

        return ByteArray(pixels.size) { i ->
            val coverage = alpha(pixels[i])
            if (coverage == 0) return@ByteArray 0
            if (plate?.get(i) == true) {
                // A dark/colored original plate can survive AOSP's white-wrapper
                // inversion. Keep it subordinate to artwork. It is part of the
                // source image, so the outer-border slider must not erode it.
                val existing = minOf(baseline[i].toInt() and 255, PLATE_ALPHA)
                return@ByteArray (existing * coverage / 255f).roundToInt().toByte()
            }
            // Single-color artwork is a silhouette. For detailed artwork keep
            // positive tonal order, with a visible floor for dark strokes. White
            // details stay at full coverage instead of being inverted to zero.
            val detail = if (high - low < 12) 255 else {
                DETAIL_ALPHA + (gray(pixels[i]) - low).coerceIn(0, high - low) *
                    (255 - DETAIL_ALPHA) / (high - low)
            }
            (detail * coverage / 255f).roundToInt().toByte()
        }
    }

    /** Baseline for generic HyperOS bitmaps that have no adaptive layer geometry. */
    fun extract(
        pixels: IntArray,
        width: Int,
        height: Int,
        inverted: Boolean,
        borderRatio: Float = 1f,
    ): ByteArray {
        var low = 255
        var high = 0
        for (pixel in pixels) {
            if (alpha(pixel) < VISIBLE_ALPHA) continue
            low = minOf(low, gray(pixel))
            high = maxOf(high, gray(pixel))
        }
        val baseline = ByteArray(pixels.size) { i ->
            val a = alpha(pixels[i])
            val value = if (low >= high) 255 else {
                val normal = (gray(pixels[i]) - low).coerceIn(0, high - low) * 255 / (high - low)
                if (inverted) 255 - normal else normal
            }
            (value * a / 255).toByte()
        }
        return refine(pixels, width, height, baseline, borderRatio)
    }

    /**
     * A plate must occupy a compact source silhouette, have one stable color on
     * most of its exterior and leave meaningful artwork behind. Flooding starts
     * only at the source's outside boundary; enclosed white areas are not seeds.
     */
    private fun detectExteriorPlate(pixels: IntArray, width: Int, height: Int): BooleanArray? {
        val visible = BooleanArray(pixels.size) { alpha(pixels[it]) >= VISIBLE_ALPHA }
        val outside = exteriorTransparency(visible, width, height)
        val boundary = ArrayList<Int>()
        val histogram = IntArray(4096)
        var opaqueCount = 0
        var left = width
        var right = -1
        var top = height
        var bottom = -1
        for (i in pixels.indices) {
            if (!visible[i]) continue
            opaqueCount++
            val x = i % width
            val y = i / width
            left = minOf(left, x)
            right = maxOf(right, x)
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                anyNeighbor(i, width, height) { outside[it] }
            ) {
                boundary += i
                histogram[colorBin(pixels[i])]++
            }
        }
        if (opaqueCount < 8 || boundary.isEmpty()) return null
        val boxArea = (right - left + 1) * (bottom - top + 1)
        if (opaqueCount < boxArea * 0.70f) return null
        val dominant = histogram.indices.maxByOrNull { histogram[it] } ?: return null
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (i in boundary) {
            val color = pixels[i]
            if (colorBin(color) != dominant) continue
            red += color ushr 16 and 255
            green += color ushr 8 and 255
            blue += color and 255
            count++
        }
        val meanRed = (red / count).toInt()
        val meanGreen = (green / count).toInt()
        val meanBlue = (blue / count).toInt()
        fun matches(index: Int): Boolean {
            if (!visible[index]) return false
            val color = pixels[index]
            val dr = (color ushr 16 and 255) - meanRed
            val dg = (color ushr 8 and 255) - meanGreen
            val db = (color and 255) - meanBlue
            return dr * dr + dg * dg + db * db <= COLOR_TOLERANCE_SQUARED
        }
        // A white bubble/animal/disc with dark eyes looks statistically like a
        // plate. Color and connectivity alone cannot justify erasing its body.
        // Only a nearly rectangular, fully filled light field is strong enough
        // evidence; retain ambiguous white artwork intact at every border width.
        val lightNeutral = minOf(meanRed, meanGreen, meanBlue) >= 170 &&
            maxOf(meanRed, meanGreen, meanBlue) - minOf(meanRed, meanGreen, meanBlue) <= 24
        if (lightNeutral && (opaqueCount < boxArea * 0.97f ||
                !matches(top * width + left) || !matches(top * width + right) ||
                !matches(bottom * width + left) || !matches(bottom * width + right))
        ) return null
        val seeds = boundary.filter(::matches)
        if (seeds.size < boundary.size * 0.70f) return null
        val plate = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        for (i in seeds) {
            plate[i] = true
            queue[tail++] = i
        }
        while (head < tail) {
            val i = queue[head++]
            forEachNeighbor(i, width, height) { next ->
                if (!plate[next] && matches(next)) {
                    plate[next] = true
                    queue[tail++] = next
                }
            }
        }
        val artworkCount = opaqueCount - tail
        if (tail < opaqueCount * 0.35f || artworkCount < maxOf(4f, opaqueCount * 0.02f)) return null
        return plate
    }

    private fun exteriorTransparency(visible: BooleanArray, width: Int, height: Int): BooleanArray {
        val outside = BooleanArray(visible.size)
        val queue = IntArray(visible.size)
        var head = 0
        var tail = 0
        for (i in visible.indices) {
            val x = i % width
            val y = i / width
            if (!visible[i] && (x == 0 || y == 0 || x == width - 1 || y == height - 1)) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        while (head < tail) {
            forEachNeighbor(queue[head++], width, height) { next ->
                if (!visible[next] && !outside[next]) {
                    outside[next] = true
                    queue[tail++] = next
                }
            }
        }
        return outside
    }

    private inline fun forEachNeighbor(index: Int, width: Int, height: Int, action: (Int) -> Unit) {
        val x = index % width
        if (x > 0) action(index - 1)
        if (x + 1 < width) action(index + 1)
        if (index >= width) action(index - width)
        if (index + width < width * height) action(index + width)
    }

    private inline fun anyNeighbor(index: Int, width: Int, height: Int, predicate: (Int) -> Boolean): Boolean {
        forEachNeighbor(index, width, height) { if (predicate(it)) return true }
        return false
    }

    private fun alpha(pixel: Int): Int = pixel ushr 24
    private fun gray(pixel: Int): Int = ((pixel ushr 16 and 255) + (pixel ushr 8 and 255) + (pixel and 255)) / 3
    private fun colorBin(pixel: Int): Int = (pixel ushr 12 and 0xF00) or (pixel ushr 8 and 0xF0) or (pixel ushr 4 and 0xF)
}
