package com.jerrey.monoicon.theme.render

import org.junit.Assert.*
import org.junit.Test

class GeneratedArtworkShapeTest {
    @Test fun zeroRetainsAllSourceCoverageAndEmptyMasksAreIgnored() {
        val alpha = rectangle(80, 60, 10, 5, 70, 55)
        val shape = requireNotNull(GeneratedArtworkShape.fromAlpha(alpha, 80, 60))
        assertArrayEquals(alpha, shape.roundedAlpha(0f))
        assertArrayEquals(alpha, shape.roundedAlpha(Float.NaN))
        assertNull(GeneratedArtworkShape.fromAlpha(ByteArray(80 * 60), 80, 60))
    }

    @Test fun circleUsesArtworkBoundsRatherThanBitmapPadding() {
        val source = rectangle(120, 100, 30, 20, 90, 80)
        val original = source.copyOf()
        val rounded = requireNotNull(GeneratedArtworkShape.fromAlpha(source, 120, 100)).roundedAlpha(0.5f)
        assertEquals(0, rounded[20 * 120 + 30].toInt() and 255)
        assertEquals(255, rounded[20 * 120 + 60].toInt() and 255)
        assertEquals(255, rounded[50 * 120 + 30].toInt() and 255)
        assertEquals(255, rounded[50 * 120 + 60].toInt() and 255)
        assertArrayEquals(original, source)
        val occupied = rounded.count { it.toInt() and 255 > 0 }
        assertTrue("circle area excludes the square corners", occupied in 2750..3000)
    }

    @Test fun eachPercentContinuouslyRemovesCornersWithAntialiasedEdges() {
        val source = rectangle(120, 120, 10, 10, 110, 110)
        val shape = requireNotNull(GeneratedArtworkShape.fromAlpha(source, 120, 120))
        var previous = shape.roundedAlpha(0f)
        val totals = mutableSetOf<Long>()
        for (percent in 1..100) {
            val next = shape.roundedAlpha(percent / 200f)
            for (i in next.indices) assertTrue("coverage cannot grow with roundness",
                (next[i].toInt() and 255) <= (previous[i].toInt() and 255))
            totals += next.sumOf { (it.toInt() and 255).toLong() }
            previous = next
        }
        assertTrue("slider retains intermediate shapes", totals.size > 90)
        assertTrue("rounded outline is antialiased", previous.any { (it.toInt() and 255) in 1..254 })
    }

    @Test fun rectangularArtworkEndsInACapsuleAndRetainsHolesAndPartialAlpha() {
        val source = rectangle(140, 100, 10, 20, 130, 80)
        source[50 * 140 + 70] = 0
        source[50 * 140 + 71] = 100
        val result = requireNotNull(GeneratedArtworkShape.fromAlpha(source, 140, 100)).roundedAlpha(0.5f)
        assertEquals(0, result[20 * 140 + 10].toInt() and 255)
        assertEquals(255, result[20 * 140 + 70].toInt() and 255)
        assertEquals(255, result[50 * 140 + 10].toInt() and 255)
        assertEquals(0, result[50 * 140 + 70].toInt() and 255)
        assertEquals(100, result[50 * 140 + 71].toInt() and 255)
    }

    @Test fun roundedArtworkStillFitsOuterPlateAtAllBorderValues() {
        val source = rectangle(100, 100, 10, 10, 90, 90)
        val shape = requireNotNull(GeneratedArtworkShape.fromAlpha(source, 100, 100))
        for (artworkPercent in listOf(0, 25, 50, 75, 100)) {
            val alpha = shape.roundedAlpha(artworkPercent / 200f)
            val border = requireNotNull(GeneratedIconBorder.fromAlpha(alpha, 100, 100))
            for ((width, height) in listOf(200f to 200f, 320f to 160f, 160f to 320f)) {
                for (roundness in listOf(0, 50, 100)) for (ratio in listOf(0f, 0.5f, 1f)) {
                    val radius = minOf(width, height) * (0.25f + roundness / 400f)
                    val scale = border.glyphScale(width, height, radius, ratio) * minOf(width, height) / 100f
                    for (y in 0 until 100) for (x in 0 until 100) {
                        if (alpha[y * 100 + x].toInt() and 255 == 0) continue
                        for (edgeX in x..x + 1) for (edgeY in y..y + 1) {
                            val dx = maxOf(kotlin.math.abs((edgeX - 50f) * scale) - (width / 2f - radius), 0f)
                            val dy = maxOf(kotlin.math.abs((edgeY - 50f) * scale) - (height / 2f - radius), 0f)
                            assertTrue("rounded pattern remains in outer plate", dx * dx + dy * dy <= radius * radius + 0.02f)
                        }
                    }
                }
            }
        }
    }

    private fun rectangle(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) =
        ByteArray(width * height) { index ->
            if (index % width in left until right && index / width in top until bottom) 255.toByte() else 0
        }
}
