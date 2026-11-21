package com.jerrey.monoicon.theme.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

class GeneratedIconBorderTest {
    private val defaultFraction = 0.7f * sqrt(375f / 576f)

    @Test fun compactRectangleIsContainedAndTouchesTheRequestedPlate() {
        val sample = Sample(compactArtwork(), 16, 16)
        for (radius in listOf(0f, 2f, 4f, 8f)) assertFitEndpoint(sample, 16f, 16f, radius)

        val border = border(sample)
        assertEquals("an 8-by-8 block fits the square at its cell edges", 2f,
            border.glyphScale(16f, 16f, 0f, 0f), 0.001f)
        assertEquals("the same block fits inside the inscribed circle", sqrt(2f),
            border.glyphScale(16f, 16f, 8f, 0f), 0.001f)
    }

    @Test fun roundCornersContinuouslyReduceTheAvailableContainScale() {
        val sample = Sample(compactArtwork(), 16, 16)
        val border = border(sample)
        val scales = (0..16).map { step ->
            val radius = step / 2f
            val actual = border.glyphScale(16f, 16f, radius, 0f)
            assertEquals(expectedFitScale(sample, 16f, 16f, radius), actual, 0.001f)
            actual
        }
        scales.zipWithNext().forEach { (lessRounded, moreRounded) ->
            assertTrue("adding corner radius cannot create more room: $scales", moreRounded <= lessRounded + 0.0001f)
            assertTrue("sampled radii must not introduce a scale jump: $scales", lessRounded - moreRounded < 0.08f)
        }
    }

    @Test fun roundAndSquareSourcesUseTheirOwnOccupiedCorners() {
        val square = Sample(ByteArray(64 * 64).also {
            for (y in 16 until 48) for (x in 16 until 48) it[y * 64 + x] = -1
        }, 64, 64)
        val round = Sample(ByteArray(64 * 64).also { alpha ->
            for (i in alpha.indices) {
                val dx = i % 64 + 0.5f - 32f
                val dy = i / 64 + 0.5f - 32f
                if (dx * dx + dy * dy <= 16f * 16f) alpha[i] = -1
            }
        }, 64, 64)

        assertFitEndpoint(square, 64f, 64f, 32f)
        assertFitEndpoint(round, 64f, 64f, 32f)
        assertTrue("a round contour has more room in a round plate than square corners",
            border(round).glyphScale(64f, 64f, 32f, 0f) >
                border(square).glyphScale(64f, 64f, 32f, 0f))
    }

    @Test fun authoredOffsetsArePreservedForDenseAndSparseArtwork() {
        val dense = Sample(ByteArray(16 * 16).also {
            for (y in 1..8) for (x in 1..8) it[y * 16 + x] = -1
        }, 16, 16)
        val sparse = Sample(ByteArray(16 * 16).also {
            for (y in 1..8) {
                it[y * 16 + 1] = -1
                it[y * 16 + 8] = -1
            }
            for (x in 1..8) {
                it[1 * 16 + x] = -1
                it[8 * 16 + x] = -1
            }
        }, 16, 16)

        for (sample in listOf(dense, sparse)) {
            val original = sample.alpha.copyOf()
            assertFitEndpoint(sample, 16f, 16f, 8f)
            assertTrue("corner-authored artwork may need shrinking to remain contained",
                border(sample).glyphScale(16f, 16f, 8f, 0f) < 1f)
            assertArrayEquals("fit analysis must not recenter or rewrite the source", original, sample.alpha)
        }
        assertEquals("density inside the same authored outline does not change contain-fit",
            border(dense).glyphScale(16f, 16f, 8f, 0f),
            border(sparse).glyphScale(16f, 16f, 8f, 0f), 0.001f)
    }

    @Test fun thinAndDisconnectedPixelsUseTheirActualOccupiedCellCorners() {
        val thin = Sample(ByteArray(16 * 16).also {
            for (x in 2..13) it[8 * 16 + x] = -1
        }, 16, 16)
        val disconnected = Sample(ByteArray(16 * 16).also {
            for (y in 5..10) for (x in listOf(2, 3, 12, 13)) it[y * 16 + x] = -1
        }, 16, 16)

        for (sample in listOf(thin, disconnected)) {
            val original = sample.alpha.copyOf()
            for (radius in listOf(0f, 4f, 8f)) assertFitEndpoint(sample, 16f, 16f, radius)
            assertArrayEquals("contain-fit never fills gaps or joins disconnected strokes", original, sample.alpha)
        }
        assertTrue("different occupied corners must produce their own fit",
            abs(border(thin).glyphScale(16f, 16f, 8f, 0f) -
                border(disconnected).glyphScale(16f, 16f, 8f, 0f)) > 0.01f)
    }

    @Test fun closedFramesAndRingsKeepTheirHolesAndUseOnlyRealAlpha() {
        val frame = Sample(ByteArray(16 * 16).also {
            for (y in 3..12) for (x in 3..12) {
                if (x == 3 || x == 12 || y == 3 || y == 12) it[y * 16 + x] = -1
            }
        }, 16, 16)
        val ring = Sample(ByteArray(64 * 64).also { alpha ->
            for (i in alpha.indices) {
                val dx = i % 64 + 0.5f - 32f
                val dy = i / 64 + 0.5f - 32f
                val d2 = dx * dx + dy * dy
                if (d2 in 12f * 12f..16f * 16f) alpha[i] = -1
            }
        }, 64, 64)

        for (sample in listOf(frame, ring)) {
            val original = sample.alpha.copyOf()
            for ((width, height) in listOf(64f to 64f, 96f to 64f)) {
                assertFitEndpoint(sample, width, height, minOf(width, height) / 2f)
            }
            assertArrayEquals("closed transparent interiors remain authored transparency", original, sample.alpha)
            assertEquals("the center hole remains empty", 0,
                sample.alpha[(sample.height / 2) * sample.width + sample.width / 2].toInt())
        }
    }

    @Test fun equivalentGeometryAcrossSourceSizesHasTheSameScale() {
        fun centeredBlock(side: Int): Sample = Sample(ByteArray(side * side).also {
            for (y in side / 4 until side * 3 / 4) for (x in side / 4 until side * 3 / 4) it[y * side + x] = -1
        }, side, side)

        val samples = listOf(centeredBlock(16), centeredBlock(32), centeredBlock(64))
        for (radius in listOf(0f, 40f, 80f)) {
            val scales = samples.map { sample ->
                assertFitEndpoint(sample, 160f, 160f, radius)
                border(sample).glyphScale(160f, 160f, radius, 0f)
            }
            scales.drop(1).forEach { assertEquals("source resolution must not change relative fit", scales[0], it, 0.001f) }
        }
    }

    @Test fun rectangularSourcesAndTilesUseOneUniformScale() {
        val source = Sample(ByteArray(16 * 32).also {
            for (y in 8 until 24) for (x in 4 until 12) it[y * 16 + x] = -1
        }, 16, 32)
        for ((width, height, radius) in listOf(
            Triple(160f, 160f, 0f),
            Triple(320f, 160f, 40f),
            Triple(160f, 320f, 80f),
        )) {
            assertFitEndpoint(source, width, height, radius)
            val scale = border(source).glyphScale(width, height, radius, 0f)
            assertEquals("resizing the complete target preserves the relative fit", scale,
                border(source).glyphScale(width * 2f, height * 2f, radius * 2f, 0f), 0.001f)
        }
    }

    @Test fun everyNonzeroFringePixelParticipatesInContainFit() {
        val plain = Sample(compactArtwork(), 16, 16)
        val nearFringe = Sample(compactArtwork().also { it[8 * 16 + 3] = 1 }, 16, 16)
        val fringe = Sample(compactArtwork().also { it[0] = 1 }, 16, 16)
        val original = fringe.alpha.copyOf()
        val plainScale = border(plain).glyphScale(16f, 16f, 8f, 0f)
        val fringeScale = border(fringe).glyphScale(16f, 16f, 8f, 0f)

        assertFitEndpoint(nearFringe, 16f, 16f, 0f)
        assertEquals("a sub-threshold fringe affects fit without redefining the default extent",
            defaultFraction * 2f, border(nearFringe).glyphScale(16f, 16f, 0f, 1f), 0.001f)
        assertFitEndpoint(fringe, 16f, 16f, 8f)
        assertTrue("even alpha=1 at a corner constrains the actual rendered contour", fringeScale < plainScale)
        assertTrue("a corner fringe can require scaling below authored size", fringeScale < 1f)
        assertEquals("default normalization is capped by the same no-clipping fit", fringeScale,
            border(fringe).glyphScale(16f, 16f, 8f, 1f), 0.001f)
        for (ratio in listOf(0.25f, 0.5f, 0.75f)) {
            assertEquals("when both endpoints hit the fit ceiling interpolation remains contained and flat",
                fringeScale, border(fringe).glyphScale(16f, 16f, 8f, ratio), 0.001f)
        }
        assertArrayEquals("fringe alpha remains unchanged", original, fringe.alpha)
    }

    @Test fun defaultUsesTheSharedExtentButNeverExceedsContainFit() {
        val compact = Sample(compactArtwork(), 16, 16)
        val faint = Sample(compactArtwork().map {
            if (it == 0.toByte()) 0.toByte() else 31.toByte()
        }.toByteArray(), 16, 16)
        val fullBleed = Sample(ByteArray(16 * 16) { -1 }, 16, 16)
        val corner = Sample(ByteArray(16 * 16).also {
            for (y in 0..4) for (x in 0..4) it[y * 16 + x] = -1
        }, 16, 16)

        for (sample in listOf(compact, faint, fullBleed, corner)) {
            for (radius in listOf(0f, 4f, 8f)) {
                val expected = minOf(expectedDefaultScale(sample, 16f, 16f),
                    expectedFitScale(sample, 16f, 16f, radius))
                val actual = border(sample).glyphScale(16f, 16f, radius, 1f)
                assertEquals("100% uses the common default extent with a geometry fit ceiling", expected, actual, 0.001f)
                assertContained(sample, 16f, 16f, radius, actual)
            }
        }
        assertEquals(defaultFraction * 2f, border(compact).glyphScale(16f, 16f, 0f, 1f), 0.001f)
        assertEquals(defaultFraction, border(fullBleed).glyphScale(16f, 16f, 0f, 1f), 0.001f)
        assertEquals("full-bleed corners require a below-one fit in a circular plate", sqrt(0.5f),
            border(fullBleed).glyphScale(16f, 16f, 8f, 0f), 0.001f)
    }

    @Test fun intermediateRatiosLinearlyInterpolateBetweenContainedEndpoints() {
        val sample = Sample(compactArtwork(), 16, 16)
        for ((width, height, radius) in listOf(
            Triple(16f, 16f, 0f),
            Triple(16f, 16f, 8f),
            Triple(32f, 16f, 8f),
        )) {
            val border = border(sample)
            val fit = border.glyphScale(width, height, radius, 0f)
            val default = border.glyphScale(width, height, radius, 1f)
            for (ratio in listOf(0.25f, 0.5f, 0.75f)) {
                val actual = border.glyphScale(width, height, radius, ratio)
                assertEquals("border ratio is a continuous endpoint interpolation",
                    fit * (1f - ratio) + default * ratio, actual, 0.0001f)
                assertContained(sample, width, height, radius, actual)
            }
        }
    }

    @Test fun targetResizingDoesNotChangeRelativeContainGeometry() {
        val sample = Sample(ByteArray(20 * 12).also {
            for (y in 2..9) for (x in 3..16) it[y * 20 + x] = -1
        }, 20, 12)
        for (ratio in listOf(0f, 0.5f, 1f)) {
            val small = border(sample).glyphScale(100f, 60f, 15f, ratio)
            val large = border(sample).glyphScale(300f, 180f, 45f, ratio)
            assertEquals("uniform target resizing keeps the same glyph multiplier", small, large, 0.001f)
        }
    }

    @Test fun geometryChangesDoNotReuseAnotherPlatesFitOrDefaultCap() {
        val sample = Sample(compactArtwork().also { it[0] = 1 }, 16, 16)
        val border = border(sample)
        val squareFit = expectedFitScale(sample, 16f, 16f, 0f)
        val circleFit = expectedFitScale(sample, 16f, 16f, 8f)

        assertEquals(squareFit, border.glyphScale(16f, 16f, 0f, 0f), 0.001f)
        assertEquals(squareFit, border.glyphScale(16f, 16f, 0f, 1f), 0.001f)
        assertEquals(circleFit, border.glyphScale(16f, 16f, 8f, 1f), 0.001f)
        assertEquals(circleFit, border.glyphScale(16f, 16f, 8f, 0f), 0.001f)
        assertEquals("returning to the first geometry recomputes its fit", squareFit,
            border.glyphScale(16f, 16f, 0f, 0f), 0.001f)
        assertEquals("the first geometry also restores its own default ceiling", squareFit,
            border.glyphScale(16f, 16f, 0f, 1f), 0.001f)
    }

    @Test fun invalidArgumentsAndSafeRatioEndpointsArePreserved() {
        val sample = Sample(compactArtwork(), 16, 16)
        val border = border(sample)
        for (dimension in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertTrue(runCatching { border.glyphScale(dimension, 16f, 8f, 0f) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { border.glyphScale(16f, dimension, 8f, 1f) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { GeneratedIconBorder.fromAlpha(ByteArray(15), 4, 4) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { GeneratedIconBorder.fromAlpha(ByteArray(1), 0, 1) }.exceptionOrNull() is IllegalArgumentException)

        val fit = border.glyphScale(16f, 16f, 8f, 0f)
        val default = border.glyphScale(16f, 16f, 8f, 1f)
        assertEquals(fit, border.glyphScale(16f, 16f, 8f, -1f), 0f)
        assertEquals(default, border.glyphScale(16f, 16f, 8f, 2f), 0f)
        for (ratio in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(default, border.glyphScale(16f, 16f, 8f, ratio), 0f)
        }
        assertEquals(0f, GeneratedIconBorder.normalizeRatio(-1f), 0f)
        assertEquals(0.5f, GeneratedIconBorder.normalizeRatio(0.5f), 0f)
        assertEquals(1f, GeneratedIconBorder.normalizeRatio(2f), 0f)
        assertEquals(1f, GeneratedIconBorder.normalizeRatio(Float.NaN), 0f)
    }

    @Test fun emptyArtworkHasNoBorderToAdjust() {
        assertNull(GeneratedIconBorder.fromAlpha(ByteArray(16 * 16), 16, 16))
    }

    private data class Sample(val alpha: ByteArray, val width: Int, val height: Int)

    private fun border(sample: Sample): GeneratedIconBorder =
        requireNotNull(GeneratedIconBorder.fromAlpha(sample.alpha, sample.width, sample.height))

    private fun assertFitEndpoint(sample: Sample, width: Float, height: Float, radius: Float) {
        val actual = border(sample).glyphScale(width, height, radius, 0f)
        val expected = expectedFitScale(sample, width, height, radius)
        assertEquals("0% must be the largest scale that contains every occupied cell corner", expected, actual, 0.001f)
        assertContained(sample, width, height, radius, actual)
        val boundaryDistance = occupiedCorners(sample).maxOf { (x, y) ->
            roundedRectDistance(mappedX(sample, width, height, x, actual),
                mappedY(sample, width, height, y, actual), width, height, radius)
        }
        assertTrue("the limiting occupied corner should approach the plate boundary: $boundaryDistance",
            abs(boundaryDistance) < 0.003f * minOf(width, height))
    }

    private fun assertContained(sample: Sample, width: Float, height: Float, radius: Float, scale: Float) {
        occupiedCorners(sample).forEach { (x, y) ->
            val distance = roundedRectDistance(mappedX(sample, width, height, x, scale),
                mappedY(sample, width, height, y, scale), width, height, radius)
            assertTrue("occupied cell corner ($x,$y) is outside by $distance", distance <= 0.001f * minOf(width, height))
        }
    }

    private fun expectedFitScale(sample: Sample, width: Float, height: Float, radius: Float): Float {
        fun fits(scale: Float): Boolean = occupiedCorners(sample).all { (x, y) ->
            roundedRectDistance(mappedX(sample, width, height, x, scale),
                mappedY(sample, width, height, y, scale), width, height, radius) <= 0f
        }
        var low = 0f
        var high = 1f
        while (fits(high) && high < 65536f) {
            low = high
            high *= 2f
        }
        repeat(32) {
            val middle = (low + high) / 2f
            if (fits(middle)) low = middle else high = middle
        }
        return low
    }

    private fun expectedDefaultScale(sample: Sample, width: Float, height: Float): Float {
        val maximumAlpha = sample.alpha.maxOf { it.toInt() and 255 }
        val threshold = minOf(32, maximumAlpha)
        var extent = 0f
        for (i in sample.alpha.indices) {
            if ((sample.alpha[i].toInt() and 255) < threshold) continue
            val x = i % sample.width
            val y = i / sample.width
            extent = maxOf(extent,
                abs(x - sample.width / 2f), abs(x + 1f - sample.width / 2f),
                abs(y - sample.height / 2f), abs(y + 1f - sample.height / 2f))
        }
        val baseScale = minOf(width / sample.width, height / sample.height)
        return defaultFraction * minOf(width, height) / (2f * extent * baseScale)
    }

    private fun occupiedCorners(sample: Sample): List<Pair<Float, Float>> = buildList {
        for (i in sample.alpha.indices) {
            if ((sample.alpha[i].toInt() and 255) == 0) continue
            val x = i % sample.width
            val y = i / sample.width
            add(x.toFloat() to y.toFloat())
            add((x + 1f) to y.toFloat())
            add(x.toFloat() to (y + 1f))
            add((x + 1f) to (y + 1f))
        }
    }

    private fun mappedX(sample: Sample, width: Float, height: Float, x: Float, scale: Float): Float {
        val baseScale = minOf(width / sample.width, height / sample.height)
        return (x - sample.width / 2f) * baseScale * scale
    }

    private fun mappedY(sample: Sample, width: Float, height: Float, y: Float, scale: Float): Float {
        val baseScale = minOf(width / sample.width, height / sample.height)
        return (y - sample.height / 2f) * baseScale * scale
    }

    private fun roundedRectDistance(
        x: Float, y: Float, width: Float, height: Float, requestedRadius: Float,
    ): Float {
        val radius = requestedRadius.coerceIn(0f, minOf(width, height) / 2f)
        val qx = abs(x) - (width / 2f - radius)
        val qy = abs(y) - (height / 2f - radius)
        return hypot(maxOf(qx, 0f), maxOf(qy, 0f)) + minOf(maxOf(qx, qy), 0f) - radius
    }

    private fun compactArtwork(): ByteArray = ByteArray(16 * 16).also {
        for (y in 4..11) for (x in 4..11) it[y * 16 + x] = -1
    }
}
