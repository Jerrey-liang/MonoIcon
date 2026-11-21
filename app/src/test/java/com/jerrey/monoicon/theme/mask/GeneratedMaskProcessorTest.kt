package com.jerrey.monoicon.theme.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedMaskProcessorTest {
    @Test
    fun enclosedWhiteArtworkIsOpaqueAtEveryBorderWidth() {
        val size = 32
        val pixels = IntArray(size * size) { WHITE }
        fill(pixels, size, 8, 24, BLACK)
        fill(pixels, size, 12, 20, WHITE)
        val old = ByteArray(pixels.size) { if (pixels[it] == BLACK) 255.toByte() else 0 }
        for (ratio in listOf(0f, 0.25f, 0.5f, 1f)) {
            val mask = GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
            assertEquals("opaque white artwork must not become a hole", 255, mask.at(size, 16, 16))
            assertTrue("the surrounding ink stays visible", mask.at(size, 9, 16) >= 100)
            assertEquals("external white plate remains outside the glyph", 0, mask.at(size, 1, 1))
        }
    }

    @Test
    fun outerBorderSettingNeverChangesSourcePlateOrWhiteMotif() {
        val size = 40
        val pixels = IntArray(size * size)
        fill(pixels, size, 5, 35, BLUE)
        fill(pixels, size, 14, 26, WHITE)
        val old = ByteArray(pixels.size) { if (pixels[it] == BLUE) 255.toByte() else 0 }
        val masks = listOf(0f, 0.25f, 0.5f, 1f).map { ratio ->
            GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
        }
        masks.forEach { assertArrayEquals("only the rendered outer plate may change", masks.last(), it) }
        for (i in pixels.indices) {
            val expected = when (pixels[i]) {
                WHITE -> 255
                BLUE -> 80
                else -> 0
            }
            masks.forEach { assertEquals("source pixel $i", expected, it[i].toInt() and 255) }
        }
    }

    @Test
    fun darkRenderingKeepsWhiteArtworkBrighterThanTheExterior() {
        val size = 32
        val pixels = IntArray(size * size) { BLUE }
        fill(pixels, size, 10, 22, WHITE)
        val old = ByteArray(pixels.size) { if (pixels[it] == BLUE) 255.toByte() else 0 }
        fun rendered(alpha: Int) = (24 * (255 - alpha) + 214 * alpha) / 255
        assertTrue("fixture reproduces the old negative", rendered(old.at(size, 16, 16)) < rendered(old.at(size, 2, 2)))
        for (ratio in listOf(0f, 0.5f, 1f)) {
            val mask = GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
            val white = rendered(mask.at(size, 16, 16))
            val exterior = rendered(mask.at(size, 2, 2))
            assertTrue("dark themed white motif must read as foreground", white >= exterior + 100)
            assertEquals(214, white)
        }
    }

    @Test
    fun realTransparentHolesAndCoverageRemainIntact() {
        val size = 32
        val pixels = IntArray(size * size)
        fill(pixels, size, 4, 28, WHITE)
        fill(pixels, size, 12, 20, 0)
        pixels[8 * size + 8] = 0x80FFFFFF.toInt()
        val old = ByteArray(pixels.size)
        for (ratio in listOf(0f, 0.5f, 1f)) {
            val mask = GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
            assertEquals(0, mask.at(size, 16, 16))
            assertEquals(0, mask.at(size, 1, 1))
            assertEquals(128, mask.at(size, 8, 8))
            assertEquals(255, mask.at(size, 9, 9))
        }
    }

    @Test
    fun whiteOnTransparentIsArtworkEvenWhenItTouchesEveryEdge() {
        val size = 32
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (x in 13..18 || y in 13..18) WHITE else 0
        }
        val old = ByteArray(pixels.size)
        val none = GeneratedMaskProcessor.refine(pixels, size, size, old, 0f)
        val full = GeneratedMaskProcessor.refine(pixels, size, size, old, 1f)
        assertArrayEquals("no stable plate means no guessed border", full, none)
        assertEquals(255, none.at(size, 16, 0))
        assertEquals(255, none.at(size, 16, 16))
    }

    @Test
    fun irregularColoredGlyphDoesNotBecomeARemovablePlate() {
        val size = 40
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            if (x in 17..22 || y in 17..22) pixels[y * size + x] = BLUE
        }
        fill(pixels, size, 17, 23, WHITE)
        val old = ByteArray(pixels.size) { 255.toByte() }
        assertArrayEquals(
            GeneratedMaskProcessor.refine(pixels, size, size, old, 1f),
            GeneratedMaskProcessor.refine(pixels, size, size, old, 0f),
        )
    }

    @Test
    fun whiteBubbleWithDarkEyesIsArtworkNotAnExteriorPlate() {
        assertBubbleArtwork(WHITE)
    }

    @Test
    fun offWhiteAndLightGrayBodiesRemainArtworkAtEveryBorderWidth() {
        for (gray in listOf(245, 210, 170)) {
            assertBubbleArtwork(BLACK or (gray shl 16) or (gray shl 8) or gray)
        }
    }

    private fun assertBubbleArtwork(bodyColor: Int) {
        val size = 40
        val pixels = IntArray(size * size) { i ->
            val dx = i % size - 20
            val dy = i / size - 20
            if (dx * dx + dy * dy <= 16 * 16) bodyColor else 0
        }
        for (y in 15..18) for (x in listOf(15, 16, 24, 25)) pixels[y * size + x] = BLACK
        val old = ByteArray(pixels.size) { if (pixels[it] == BLACK) 255.toByte() else 0 }
        for (ratio in listOf(0f, 0.5f, 1f)) {
            val mask = GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
            for (i in pixels.indices) if (pixels[i] == bodyColor) {
                assertEquals("light body at $i must survive ratio=$ratio", 255, mask[i].toInt() and 255)
            }
            assertTrue("eyes still have visible ink", mask.at(size, 15, 16) >= 100)
        }
    }

    @Test
    fun sourceCoverageIsStableAcrossBorderSettingsAndIconResolutions() {
        for (size in listOf(60, 120)) {
            val pixels = IntArray(size * size) { BLUE }
            fill(pixels, size, size / 3, size * 2 / 3, WHITE)
            val old = ByteArray(pixels.size) { 255.toByte() }
            for (ratio in listOf(0f, 0.5f, 1f)) {
                val mask = GeneratedMaskProcessor.refine(pixels, size, size, old, ratio)
                assertEquals("the complete source footprint survives at size=$size ratio=$ratio",
                    pixels.size, mask.count { (it.toInt() and 255) > 0 })
                assertEquals(255, mask.at(size, size / 2, size / 2))
                assertEquals(80, mask.at(size, 0, 0))
            }
        }
    }

    @Test
    fun genericBitmapAndAdaptiveFinishingShareWhiteDetailProtection() {
        val size = 32
        val pixels = IntArray(size * size) { WHITE }
        fill(pixels, size, 7, 25, BLACK)
        fill(pixels, size, 12, 20, WHITE)
        val mask = GeneratedMaskProcessor.extract(pixels, size, size, inverted = true, borderRatio = 0f)
        assertEquals(255, mask.at(size, 16, 16))
        assertEquals(0, mask.at(size, 1, 1))
        assertTrue(mask.at(size, 8, 16) >= 100)
    }

    @Test
    fun compatibilityBorderParameterDoesNotMutateArtworkOrInputs() {
        val size = 20
        val pixels = IntArray(size * size) { BLUE }
        fill(pixels, size, 7, 13, WHITE)
        val old = ByteArray(pixels.size) { 255.toByte() }
        val savedPixels = pixels.copyOf()
        val savedOld = old.copyOf()
        val full = GeneratedMaskProcessor.refine(pixels, size, size, old, 1f)
        for (ratio in listOf(-1f, 0f, 0.5f, Float.NaN, Float.POSITIVE_INFINITY, 2f)) {
            assertArrayEquals(full, GeneratedMaskProcessor.refine(pixels, size, size, old, ratio))
        }
        assertArrayEquals(savedPixels, pixels)
        assertArrayEquals(savedOld, old)
    }

    private fun fill(pixels: IntArray, size: Int, from: Int, until: Int, color: Int) {
        for (y in from until until) for (x in from until until) pixels[y * size + x] = color
    }

    private fun ByteArray.at(size: Int, x: Int, y: Int): Int = this[y * size + x].toInt() and 255

    companion object {
        private const val WHITE = -1
        private const val BLACK = -0x1000000
        private const val BLUE = -0xFF7F20
    }
}
