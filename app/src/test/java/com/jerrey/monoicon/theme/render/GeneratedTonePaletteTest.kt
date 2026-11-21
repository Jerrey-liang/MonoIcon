package com.jerrey.monoicon.theme.render

import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedTonePaletteTest {
    @Test
    fun sourceToneAlwaysIncreasesRenderedLuminanceInBothPalettes() {
        for ((foreground, background) in palettes) {
            val colors = (0..255).map { tone ->
                GeneratedTonePalette.map(gray(tone), foreground, background)
            }
            colors.zipWithNext().forEach { (a, b) -> assertTrue(luminance(a) <= luminance(b)) }
            assertTrue(luminance(colors.last()) - luminance(colors.first()) > 100)
        }
    }

    @Test
    fun backgroundFacingArtworkDoesNotDisappearIntoThePlate() {
        for ((foreground, background) in palettes) {
            val endpoints = GeneratedTonePalette.endpoints(foreground, background)
            val darkForeground = luminance(foreground) < luminance(background)
            assertEquals(foreground, if (darkForeground) endpoints.dark else endpoints.light)
            val nearBackground = if (darkForeground) endpoints.light else endpoints.dark
            assertTrue(kotlin.math.abs(luminance(nearBackground) - luminance(background)) >= 25)
        }
    }

    @Test
    fun paletteMappingKeepsActualCoverageInsteadOfTurningWhiteIntoAHole() {
        for ((foreground, background) in palettes) for (alpha in listOf(0, 1, 31, 32, 128, 254, 255)) {
            for (tone in listOf(0, 128, 255)) {
                val result = GeneratedTonePalette.map(gray(tone, alpha), foreground, background)
                assertEquals(alpha, result ushr 24)
                if (alpha == 0) assertEquals(0, result)
            }
        }
    }

    @Test
    fun androidMatrixAndPurePixelMapperAgreeAndAlphaIsIdentity() {
        for ((foreground, background) in palettes) {
            val matrix = GeneratedTonePalette.colorMatrix(foreground, background)
            assertEquals(20, matrix.size)
            assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), matrix.copyOfRange(15, 20), 0f)
            for (tone in 0..255) {
                val result = GeneratedTonePalette.map(gray(tone, 128), foreground, background)
                for ((row, shift) in listOf(0 to 16, 5 to 8, 10 to 0)) {
                    val expected = (matrix[row] * tone + matrix[row + 4]).roundToInt()
                    assertTrue(kotlin.math.abs(expected - (result ushr shift and 255)) <= 1)
                }
            }
        }
    }

    private fun gray(tone: Int, alpha: Int = 255): Int =
        (alpha shl 24) or (tone shl 16) or (tone shl 8) or tone

    private fun luminance(color: Int): Int = (
        (color ushr 16 and 255) * 2126 + (color ushr 8 and 255) * 7152 + (color and 255) * 722
        ) / 10000

    private val palettes = listOf(
        0xFF27171D.toInt() to 0xFFFFDAE8.toInt(),
        0xFFF8B2CB.toInt() to 0xFF342128.toInt(),
    )
}
