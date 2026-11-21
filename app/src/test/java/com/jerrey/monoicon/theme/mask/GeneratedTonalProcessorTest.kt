package com.jerrey.monoicon.theme.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedTonalProcessorTest {
    @Test
    fun blackWhiteAndMidtonesKeepTheirPositiveOrderAndCoverage() {
        val source = intArrayOf(gray(0), gray(64), gray(128), gray(192), gray(255))
        assertArrayEquals(source, GeneratedMaskProcessor.tonal(source, source.size, 1))
        assertFalse(GeneratedMaskProcessor.isSingleTone(source))
    }

    @Test
    fun opaqueWhiteFieldsAndEnclosedWhiteDetailsRemainCovered() {
        val size = 9
        val source = IntArray(size * size) { gray(255) }
        for (y in 2..6) for (x in 2..6) source[y * size + x] = gray(0)
        source[4 * size + 4] = gray(255)
        val result = GeneratedMaskProcessor.tonal(source, size, size)
        assertArrayEquals(source, result)
        assertTrue(result.all { it ushr 24 == 255 })
    }

    @Test
    fun sourceAlphaSurvivesAtEveryCoverageIncludingRealHoles() {
        val coverage = listOf(0, 1, 31, 32, 64, 128, 254, 255)
        val source = coverage.map { gray(128, it) }.toIntArray()
        val result = GeneratedMaskProcessor.tonal(source, source.size, 1)
        coverage.forEachIndexed { i, alpha -> assertEquals(alpha, result[i] ushr 24) }
        assertEquals(0, result[0])
        assertTrue(GeneratedMaskProcessor.isSingleTone(source))
    }

    @Test
    fun hiddenTransparentRgbCannotChangeTheNormalizedRange() {
        val source = intArrayOf(gray(80), gray(160), 0x00FFFFFF, 0x00000000)
        val result = GeneratedMaskProcessor.tonal(source, 4, 1)
        assertArrayEquals(intArrayOf(gray(0), gray(255), 0, 0), result)
    }

    @Test
    fun weightedLuminancePreservesColoredDetailsWithoutEqualWeightArtifacts() {
        val source = intArrayOf(gray(0), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), gray(255))
        val result = GeneratedMaskProcessor.tonal(source, 5, 1)
        assertArrayEquals(intArrayOf(gray(0), gray(54), gray(182), gray(18), gray(255)), result)
    }

    @Test
    fun nearFlatArtworkDoesNotAmplifyNoiseOrChangeInput() {
        val source = intArrayOf(gray(90), gray(95), gray(100), gray(0, 1))
        val saved = source.copyOf()
        assertTrue(GeneratedMaskProcessor.isSingleTone(source))
        val result = GeneratedMaskProcessor.tonal(source, 4, 1)
        assertArrayEquals(intArrayOf(gray(255), gray(255), gray(255), gray(255, 1)), result)
        assertArrayEquals(saved, source)
    }

    @Test
    fun premultipliedEdgeRoundingDoesNotTurnNearFlatArtworkIntoTonalDetail() {
        // Android Canvas readback of a 64/69 gray disc: edge quantization
        // broadens the observed 5-level source range to 56..69.
        val source = intArrayOf(gray(64), gray(69), gray(56, 32), gray(57, 40), gray(63, 250))
        val saved = source.copyOf()
        assertTrue(GeneratedMaskProcessor.isSingleTone(source))
        assertArrayEquals(intArrayOf(gray(255), gray(255), gray(255, 32), gray(255, 40), gray(255, 250)),
            GeneratedMaskProcessor.tonal(source, source.size, 1))
        assertArrayEquals(saved, source)
    }

    @Test
    fun roundingAllowanceKeepsTheOpaqueTwelveLevelThresholdAndTranslucentDetails() {
        assertTrue(GeneratedMaskProcessor.isSingleTone(intArrayOf(gray(64), gray(75))))
        val opaqueDetail = intArrayOf(gray(64), gray(76))
        assertFalse(GeneratedMaskProcessor.isSingleTone(opaqueDetail))
        assertArrayEquals(intArrayOf(gray(0), gray(255)), GeneratedMaskProcessor.tonal(opaqueDetail, 2, 1))
        val translucentDetail = intArrayOf(gray(64), gray(255, 32), gray(56, 32))
        assertFalse("rounding tolerance cannot hide a real white edge detail",
            GeneratedMaskProcessor.isSingleTone(translucentDetail))
        val tonal = GeneratedMaskProcessor.tonal(translucentDetail, 3, 1)
        assertEquals(gray(255, 32), tonal[1])
        assertEquals(32, tonal[2] ushr 24)
    }

    @Test
    fun entirelyLowOpacityArtworkStillRetainsItsTonalDetail() {
        val source = intArrayOf(gray(40, 12), gray(200, 24), 0x00FFFFFF)
        assertFalse(GeneratedMaskProcessor.isSingleTone(source))
        assertArrayEquals(intArrayOf(gray(0, 12), gray(255, 24), 0), GeneratedMaskProcessor.tonal(source, 3, 1))
    }

    private fun gray(tone: Int, alpha: Int = 255): Int =
        (alpha shl 24) or (tone shl 16) or (tone shl 8) or tone
}
