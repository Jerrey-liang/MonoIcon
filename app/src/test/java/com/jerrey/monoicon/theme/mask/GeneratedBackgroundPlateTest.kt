package com.jerrey.monoicon.theme.mask

import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedBackgroundPlateTest {
    @Test fun constantAndSmoothLinearAndRadialGradientsArePlates() {
        val size = 128
        val fields = listOf(
            field(size) { _, _ -> rgb(255, 110, 158) },
            field(size) { _, y -> rgb(255, 110 - y / 2, 158 - y / 3) },
            field(size) { x, y -> rgb(220 - (x + y) / 3, 170 + (x + y) / 4, 140) },
            field(size) { x, y ->
                val tone = (60 + hypot(x - size / 2.0, y - size / 2.0)).roundToInt()
                rgb(tone, tone + 20, tone + 40)
            },
        )
        fields.forEach { assertTrue(GeneratedBackgroundPlate.isSmoothPlate(it, size, size)) }
    }

    @Test fun rasterizedPlateEdgeRoundingDoesNotTurnAGradientIntoArtwork() {
        val size = 128
        val gradient = field(size) { x, y ->
            val edge = x == 0 || y == 0 || x == size - 1 || y == size - 1
            rgb(255, 110 - y / 2 + if (edge) 7 else 0, 158 - y / 3, if (edge) 245 else 255)
        }
        assertTrue(GeneratedBackgroundPlate.isSmoothPlate(gradient, size, size))
    }

    @Test fun transparencyAndBackgroundLayerShapesRemainArtwork() {
        val size = 64
        val empty = IntArray(size * size)
        val translucent = IntArray(size * size) { rgb(220, 190, 200, 200) }
        val alphaMotif = field(size) { x, y -> rgb(255, 255, 255, if (x in 16..47 && y in 16..47) 255 else 0) }
        val subtleAlphaMotif = field(size) { x, y -> rgb(255, 255, 255, if (x == 32 && y == 32) 240 else 255) }
        for (pixels in listOf(empty, translucent, alphaMotif, subtleAlphaMotif)) {
            assertFalse(GeneratedBackgroundPlate.isSmoothPlate(pixels, size, size))
        }
    }

    @Test fun sharpLogoEdgesAndSmallDetailsAreNotSmoothPlates() {
        val size = 64
        val logo = field(size) { x, y -> if (x in 20..43 && y in 20..43) rgb(255, 255, 255) else rgb(20, 20, 20) }
        val detail = field(size) { x, y -> if (x == 31 && y == 31) rgb(0, 0, 0) else rgb(255, 255, 255) }
        val edgeDetail = field(size) { x, y -> if (x == 0 && y == 31) rgb(0, 0, 0) else rgb(255, 255, 255) }
        for (pixels in listOf(logo, detail, edgeDetail)) {
            assertFalse(GeneratedBackgroundPlate.isSmoothPlate(pixels, size, size))
        }
    }

    @Test fun fineTextureFailsEvenWhenEveryAdjacentChangeIsSmall() {
        val size = 64
        val texture = field(size) { x, y ->
            val tone = 128 + if ((x + y) % 2 == 0) 3 else -3
            rgb(tone, tone, tone)
        }
        assertFalse(GeneratedBackgroundPlate.isSmoothPlate(texture, size, size))
    }

    @Test fun smallImagesInspectEveryPixelAndKeepDistinctGeometry() {
        assertTrue(GeneratedBackgroundPlate.isSmoothPlate(intArrayOf(rgb(250, 250, 250)), 1, 1))
        assertFalse(GeneratedBackgroundPlate.isSmoothPlate(intArrayOf(rgb(250, 250, 250), rgb(0, 0, 0)), 2, 1))
        assertFalse(GeneratedBackgroundPlate.isSmoothPlate(intArrayOf(rgb(250, 250, 250), 0), 2, 1))
    }

    private fun field(size: Int, pixel: (Int, Int) -> Int): IntArray =
        IntArray(size * size) { pixel(it % size, it / size) }

    private fun rgb(red: Int, green: Int, blue: Int, alpha: Int = 255): Int =
        (alpha shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)
}
