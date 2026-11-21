package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.theme.mask.AospMonochromeFactory
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.GeneratedTonePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Canvas regressions: RGB is source tone, alpha is physical artwork coverage. */
@RunWith(AndroidJUnit4::class)
class GeneratedTonalRenderingInstrumentedTest {
    @Test
    fun whiteArtworkStaysBrighterThanDarkInkAndDistinctFromATransparentHoleInBothPalettes() {
        val source = compactArtwork()
        val artwork = requireNotNull(LabMonochromeExtractor.extractFromBitmap(source, -0.5))
        try {
            assertEquals(Bitmap.Config.ARGB_8888, artwork.config)
            assertEquals("black ink is opaque coverage", 255, Color.alpha(artwork.getPixel(34, 48)))
            assertEquals("white body is opaque coverage", 255, Color.alpha(artwork.getPixel(62, 48)))
            assertEquals("the center is a real transparent hole", 0, Color.alpha(artwork.getPixel(48, 48)))
            for ((foreground, background) in palettes) {
                val image = render(drawable(artwork, foreground, background), 96, 96)
                try {
                    val black = image.getPixel(34, 48)
                    val white = image.getPixel(62, 48)
                    val hole = image.getPixel(48, 48)
                    assertTrue("source white stays brighter than black for both palette polarities",
                        luminance(white) - luminance(black) > 100)
                    assertEquals("true hole reveals the theme plate", background, hole)
                    assertTrue("opaque white body cannot read as a hole", kotlin.math.abs(luminance(white) - luminance(hole)) > 25)
                    assertEquals(255, Color.alpha(black))
                    assertEquals(255, Color.alpha(white))
                } finally { image.recycle() }
            }
        } finally {
            artwork.recycle()
            source.recycle()
        }
    }

    @Test
    fun colorMatrixKeepsEveryOddWidthRowAndPartialAlpha() {
        val width = 31
        val height = 29
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                val coverage = listOf(0, 1, 31, 32, 64, 128, 254, 255)[(x + y) % 8]
                val tone = if (x < width / 2) 0 else 255
                setPixel(x, y, Color.argb(coverage, tone, tone, tone))
            }
        }
        val artwork = requireNotNull(LabMonochromeExtractor.extractFromBitmap(source, null))
        try {
            for ((foreground, background) in palettes) {
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val paint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(GeneratedTonePalette.colorMatrix(foreground, background))
                    }
                    Canvas(image).drawBitmap(artwork, 0f, 0f, paint)
                    for (y in 0 until height) for (x in 0 until width) {
                        val expected = Color.alpha(source.getPixel(x, y))
                        assertEquals("extracted coverage at $x,$y", expected, Color.alpha(artwork.getPixel(x, y)))
                        assertEquals("filtered coverage at $x,$y", expected, Color.alpha(image.getPixel(x, y)))
                    }
                } finally { image.recycle() }
            }
        } finally {
            artwork.recycle()
            source.recycle()
        }
    }

    @Test
    fun adaptiveRasterizationPreservesRgbToneAlongsideCoverage() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(0f, 0f, 48f, 96f, Paint().apply { color = Color.BLACK })
        }
        val raster = requireNotNull(AospMonochromeFactory.rasterizeClipped(source, 63))
        try {
            assertEquals(63, raster.width)
            assertEquals(63, raster.height)
            assertEquals("the geometry stage must not discard grayscale RGB", Bitmap.Config.ARGB_8888, raster.config)
            assertEquals(Color.BLACK, raster.getPixel(16, 31))
            assertEquals(Color.WHITE, raster.getPixel(47, 31))
        } finally {
            raster.recycle()
            source.recycle()
        }
    }

    @Test
    fun artworkSplitAcrossAdaptiveLayersSurvivesWrappingAndBothPalettes() {
        val adaptive = AdaptiveIconDrawable(
            paintedLayer { canvas, paint ->
                paint.color = Color.WHITE
                canvas.drawRect(24f, 32f, 40f, 64f, paint)
            },
            paintedLayer { canvas, paint ->
                paint.color = Color.BLACK
                canvas.drawRect(56f, 32f, 72f, 64f, paint)
            },
        )
        val artwork = requireNotNull(AospMonochromeFactory.wrap(adaptive, 96))
        try {
            assertEquals(Bitmap.Config.ARGB_8888, artwork.config)
            // AOSP's 1.5x viewport maps source x=32/64 to output x=24/72.
            assertEquals("the background's white motif remains opaque", Color.WHITE, artwork.getPixel(24, 48))
            assertEquals("the foreground's dark motif remains opaque", Color.BLACK, artwork.getPixel(72, 48))
            assertEquals("the gap between layers is truly transparent", 0, Color.alpha(artwork.getPixel(48, 48)))
            for ((foreground, background) in palettes) {
                val image = render(drawable(artwork, foreground, background), 96, 96)
                try {
                    val endpoints = GeneratedTonePalette.endpoints(foreground, background)
                    assertTrue("background-layer artwork keeps its light tone in either palette",
                        closeColor(image.getPixel(24, 48), endpoints.light))
                    assertTrue("foreground-layer artwork keeps its dark tone in either palette",
                        closeColor(image.getPixel(72, 48), endpoints.dark))
                    assertEquals("only the actual gap reveals the theme plate", background, image.getPixel(48, 48))
                } finally { image.recycle() }
            }
        } finally { artwork.recycle() }
    }

    @Test
    fun sparseSingleToneForegroundCannotHideStructuredBackgroundInStrategy() {
        val foreground = sparseGlyph()
        assertTrue("the fixture would otherwise enter the foreground-only silhouette path",
            requireNotNull(AospMonochromeFactory.foregroundStructure(foreground, 96)).useSilhouette)
        val adaptive = AdaptiveIconDrawable(
            paintedLayer { canvas, paint ->
                paint.color = Color.WHITE
                canvas.drawRect(24f, 32f, 40f, 64f, paint)
            },
            foreground,
        )
        // Null identity skips package lookup and caching; this also runs in app_process.
        val result = requireNotNull(AospMonochromeMaskStrategy(generatedBorderRatio = 1f).generate(adaptive, null))
        val artwork = requireNotNull(result.mask)
        try {
            assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, result.source)
            assertTrue("both layers need RGB tone and source coverage", result.tonal)
            assertEquals("background motif must survive the strategy decision", Color.WHITE,
                artwork.getPixel(artwork.width / 4, artwork.height / 2))
            assertEquals("foreground glyph must also survive", Color.BLACK,
                artwork.getPixel(artwork.width * 2 / 3, artwork.height / 2))
            for ((ink, plate) in palettes) {
                val image = render(drawable(artwork, ink, plate, tonal = result.tonal), 96, 96)
                try {
                    val endpoints = GeneratedTonePalette.endpoints(ink, plate)
                    assertTrue(closeColor(image.getPixel(24, 48), endpoints.light))
                    assertTrue(closeColor(image.getPixel(64, 48), endpoints.dark))
                } finally { image.recycle() }
            }
        } finally { artwork.recycle() }
    }

    @Test
    fun equalAverageRgbColorsRetainDifferentTonesThroughStrategy() {
        val foreground = splitDisc(Color.RED, Color.GREEN)
        val structure = requireNotNull(AospMonochromeFactory.foregroundStructure(foreground, 96))
        assertEquals("red and green have the same old average-RGB gray", 0f, structure.grayStd, 0.1f)
        assertTrue("the old transparency gates accept this fixture",
            structure.flatTransparentFraction >= 0.05f && structure.bboxTransparentFraction >= 0.05f)
        val result = requireNotNull(AospMonochromeMaskStrategy(generatedBorderRatio = 1f).generate(
            AdaptiveIconDrawable(ColorDrawable(Color.WHITE), foreground), null,
        ))
        val artwork = requireNotNull(result.mask)
        try {
            assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, result.source)
            assertTrue("Rec.709 distinguishes the two source colors", result.tonal)
            assertEquals(Bitmap.Config.ARGB_8888, artwork.config)
            assertEquals(Color.BLACK, artwork.getPixel(artwork.width / 4, artwork.height / 2))
            assertEquals(Color.WHITE, artwork.getPixel(artwork.width * 3 / 4, artwork.height / 2))
            for ((ink, plate) in palettes) {
                val image = render(drawable(artwork, ink, plate, tonal = result.tonal), 96, 96)
                try {
                    assertTrue("green remains brighter than red in either theme polarity",
                        luminance(image.getPixel(72, 48)) - luminance(image.getPixel(24, 48)) > 100)
                } finally { image.recycle() }
            }
        } finally { artwork.recycle() }
    }

    @Test
    fun smallWhiteDetailSurvivesLowStandardDeviationThroughStrategy() {
        val foreground = paintedLayer { canvas, paint ->
            paint.color = Color.rgb(64, 64, 64)
            canvas.drawCircle(48f, 48f, 30f, paint)
            // Less than 1% of the opaque disc, so the old std stays below 20.
            paint.color = Color.WHITE
            canvas.drawRect(46f, 46f, 50f, 50f, paint)
        }
        val structure = requireNotNull(AospMonochromeFactory.foregroundStructure(foreground, 96))
        assertTrue("a small but high-contrast detail passes the old std gate", structure.grayStd < 20f)
        assertTrue("the old transparency gates accept this fixture",
            structure.flatTransparentFraction >= 0.05f && structure.bboxTransparentFraction >= 0.05f)
        val result = requireNotNull(AospMonochromeMaskStrategy(generatedBorderRatio = 1f).generate(
            AdaptiveIconDrawable(ColorDrawable(Color.WHITE), foreground), null,
        ))
        val artwork = requireNotNull(result.mask)
        try {
            assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, result.source)
            assertTrue("detail area must not decide whether source tones survive", result.tonal)
            val detail = artwork.getPixel(artwork.width / 2, artwork.height / 2)
            val body = artwork.getPixel(artwork.width / 4, artwork.height / 2)
            assertEquals("white detail remains source coverage", 255, Color.alpha(detail))
            assertEquals("dark body remains source coverage", 255, Color.alpha(body))
            assertTrue("the small white detail keeps its contrast", Color.red(detail) - Color.red(body) > 180)
            assertEquals("actual exterior transparency stays transparent", 0,
                Color.alpha(artwork.getPixel(artwork.width / 8, artwork.height / 8)))
            for ((ink, plate) in palettes) {
                val image = render(drawable(artwork, ink, plate, tonal = result.tonal), 96, 96)
                try {
                    assertTrue("white detail remains brighter than the body in either palette",
                        luminance(image.getPixel(48, 48)) - luminance(image.getPixel(24, 48)) > 100)
                } finally { image.recycle() }
            }
        } finally { artwork.recycle() }
    }

    @Test
    fun nearSingleToneNoiseRetainsTheExistingStrategySilhouette() {
        val foreground = splitDisc(Color.rgb(64, 64, 64), Color.rgb(69, 69, 69))
        val structure = requireNotNull(AospMonochromeFactory.foregroundStructure(foreground, 96))
        assertTrue("small tone variation still qualifies for the established silhouette", structure.useSilhouette)
        val result = requireNotNull(AospMonochromeMaskStrategy(generatedBorderRatio = 1f).generate(
            AdaptiveIconDrawable(ColorDrawable(Color.WHITE), foreground), null,
        ))
        val artwork = requireNotNull(result.mask)
        try {
            assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE, result.source)
            assertTrue("near-flat noise is not amplified into artwork", !result.tonal)
            assertEquals(Bitmap.Config.ALPHA_8, artwork.config)
            val expected = requireNotNull(AospMonochromeFactory.renderForegroundSilhouette(foreground, artwork.width))
            try {
                assertTrue("the original geometry and alpha threshold remain unchanged", expected.sameAs(artwork))
            } finally { expected.recycle() }
        } finally { artwork.recycle() }
    }

    @Test
    fun translucentForegroundCompositesOverBackgroundBeforeTonalExtraction() {
        val adaptive = AdaptiveIconDrawable(
            paintedLayer { canvas, paint ->
                paint.color = Color.BLACK
                canvas.drawRect(24f, 24f, 44f, 72f, paint)
                paint.color = Color.WHITE
                canvas.drawRect(52f, 24f, 72f, 72f, paint)
            },
            paintedLayer { canvas, paint ->
                paint.color = Color.argb(128, 255, 255, 255)
                canvas.drawRect(24f, 40f, 52f, 56f, paint)
                paint.color = Color.argb(128, 0, 0, 0)
                canvas.drawRect(52f, 40f, 72f, 56f, paint)
            },
        )
        val artwork = requireNotNull(AospMonochromeFactory.wrap(adaptive, 96))
        try {
            assertEquals(Bitmap.Config.ARGB_8888, artwork.config)
            // Uncovered black/white retain the full range: these midtones are
            // SRC_OVER results, not a contrast stretch of the foreground alone.
            assertEquals(Color.BLACK, artwork.getPixel(24, 24))
            assertEquals(Color.WHITE, artwork.getPixel(72, 24))
            assertTrue("50% white over opaque black is opaque middle gray",
                closeColor(artwork.getPixel(24, 48), Color.rgb(128, 128, 128)))
            assertTrue("50% black over opaque white is opaque middle gray",
                closeColor(artwork.getPixel(72, 48), Color.rgb(127, 127, 127)))
            val overGap = artwork.getPixel(48, 48)
            assertEquals("the same foreground over a real hole keeps half coverage", 128, Color.alpha(overGap))
            assertEquals("partial coverage does not darken the source's white RGB", 255, Color.red(overGap))
        } finally { artwork.recycle() }
    }

    @Test
    fun uniformAndTransparentBackgroundsKeepTheForegroundSilhouettePath() {
        // wrap preserves edge coverage; the strategy's silhouette path applies
        // its alpha threshold. Compare each path only with its own baseline.
        val expected = requireNotNull(AospMonochromeFactory.wrap(
            AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), sparseGlyph()), 96,
        ))
        try {
            for (plate in listOf(Color.WHITE, Color.BLACK, Color.rgb(128, 80, 192),
                Color.argb(128, 128, 80, 192), Color.TRANSPARENT)) {
                val adaptive = AdaptiveIconDrawable(ColorDrawable(plate), sparseGlyph())
                val wrapped = requireNotNull(AospMonochromeFactory.wrap(adaptive, 96))
                try {
                    assertEquals(Bitmap.Config.ALPHA_8, wrapped.config)
                    assertTrue("a uniform plate must not add coverage to the generated artwork", expected.sameAs(wrapped))
                    assertEquals("wrap leaves background-only pixels transparent", 0, Color.alpha(wrapped.getPixel(24, 48)))
                    assertEquals("wrap retains the opaque glyph interior", 255, Color.alpha(wrapped.getPixel(64, 48)))
                } finally { wrapped.recycle() }
                val result = requireNotNull(AospMonochromeMaskStrategy(generatedBorderRatio = 1f).generate(adaptive, null))
                val mask = requireNotNull(result.mask)
                try {
                    assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE, result.source)
                    assertTrue("a single-tone glyph keeps its authored-style tint contract", !result.tonal)
                    assertEquals("background-only pixels are still transparent", 0,
                        Color.alpha(mask.getPixel(mask.width / 4, mask.height / 2)))
                    assertEquals("the foreground glyph still has full coverage", 255,
                        Color.alpha(mask.getPixel(mask.width * 2 / 3, mask.height / 2)))
                    val silhouette = requireNotNull(AospMonochromeFactory.renderForegroundSilhouette(sparseGlyph(), mask.width))
                    try {
                        assertTrue("strategy retains the existing silhouette geometry and alpha threshold", silhouette.sameAs(mask))
                    } finally { silhouette.recycle() }
                } finally { mask.recycle() }
            }
        } finally { expected.recycle() }
    }

    @Test
    fun generatedBorderZoomKeepsTonalContrastAndTheExactOuterAlpha() {
        val artwork = compactArtwork()
        val saved = requireNotNull(artwork.copy(Bitmap.Config.ARGB_8888, false))
        try {
            for ((foreground, background) in palettes) for (roundness in listOf(0, 100)) {
                val full = render(drawable(artwork, foreground, background, roundness = roundness), 192, 192)
                val zoomed = render(drawable(artwork, foreground, background, ratio = 0f, roundness = roundness), 192, 192)
                try {
                    assertSameOuterAlpha(full, zoomed)
                    assertTrue("0% border expands covered artwork inside the fixed plate",
                        countArtwork(zoomed, background) > countArtwork(full, background))
                    for (image in listOf(full, zoomed)) {
                        assertEquals("zoom preserves the true center hole", background, image.getPixel(96, 96))
                        assertTonalEndpointsPresent(image, foreground, background)
                    }
                } finally {
                    full.recycle()
                    zoomed.recycle()
                }
            }
            assertTrue("border rendering never mutates RGB tone or source coverage", saved.sameAs(artwork))
        } finally {
            saved.recycle()
            artwork.recycle()
        }
    }

    @Test
    fun largeTilesAndClonesRetainTonalModeAcrossBoundsChanges() {
        val artwork = compactArtwork()
        try {
            for ((foreground, background) in palettes) for (ratio in listOf(0f, 1f)) {
                for ((width, height) in listOf(192 to 96, 96 to 192)) {
                    val original = drawable(artwork, foreground, background, ratio).forLargeIcon(width, height)
                    val clone = original.constantState.newDrawable()
                    val expected = render(original, width, height)
                    val actual = render(clone, width, height)
                    try {
                        assertEquals(width, clone.intrinsicWidth)
                        assertEquals(height, clone.intrinsicHeight)
                        assertTrue("constant state retains both tonal filter and geometry", expected.sameAs(actual))
                        assertTonalEndpointsPresent(actual, foreground, background)
                        render(original, height, width).recycle()
                        val restored = render(original, width, height)
                        try { assertTrue("returning to earlier bounds restores the tonal image", expected.sameAs(restored)) }
                        finally { restored.recycle() }
                    } finally {
                        expected.recycle()
                        actual.recycle()
                    }
                }
            }
        } finally { artwork.recycle() }
    }

    @Test
    fun authorAlphaMasksRemainSingleTintEvenWhenStoredAsArgbAndCloned() {
        val mask = compactArtwork()
        try {
            for ((foreground, background) in palettes) {
                val authorMask = drawable(mask, foreground, background, tonal = false)
                val image = render(authorMask.constantState.newDrawable(), 96, 96)
                try {
                    assertEquals("black RGB does not change an author alpha mask", foreground, image.getPixel(34, 48))
                    assertEquals("white RGB receives exactly the same author tint", foreground, image.getPixel(62, 48))
                    assertEquals(background, image.getPixel(48, 48))
                } finally { image.recycle() }
            }
        } finally { mask.recycle() }
    }

    private fun compactArtwork(): Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawRect(20f, 20f, 76f, 76f, Paint().apply { color = Color.WHITE })
        canvas.drawRect(28f, 40f, 40f, 56f, Paint().apply { color = Color.BLACK })
        for (y in 44 until 52) for (x in 44 until 52) setPixel(x, y, Color.TRANSPARENT)
    }

    private fun sparseGlyph(): Drawable = paintedLayer { canvas, paint ->
        paint.color = Color.BLACK
        canvas.drawRect(56f, 32f, 62f, 64f, paint)
        canvas.drawRect(66f, 32f, 72f, 64f, paint)
        canvas.drawRect(56f, 46f, 72f, 50f, paint)
    }

    private fun splitDisc(left: Int, right: Int): Drawable = paintedLayer { canvas, paint ->
        paint.color = left
        canvas.drawCircle(48f, 48f, 30f, paint)
        val save = canvas.save()
        try {
            canvas.clipRect(48f, 0f, 96f, 96f)
            paint.color = right
            canvas.drawCircle(48f, 48f, 30f, paint)
        } finally { canvas.restoreToCount(save) }
    }

    /** A resource-free 96-unit layer, usable in both instrumentation and app_process. */
    private fun paintedLayer(painter: (Canvas, Paint) -> Unit): Drawable = object : Drawable() {
        private val paint = Paint()

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try {
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 96f, bounds.height() / 96f)
                painter(canvas, paint)
            } finally { canvas.restoreToCount(save) }
        }

        override fun getIntrinsicWidth(): Int = 96
        override fun getIntrinsicHeight(): Int = 96
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun drawable(
        artwork: Bitmap,
        foreground: Int,
        background: Int,
        ratio: Float = 1f,
        roundness: Int = 0,
        tonal: Boolean = true,
    ) = ColoredMonochromeDrawable(
        artwork, foreground, background, roundnessPercent = roundness, sizePercent = 100,
        dynamicColors = false, generatedBorderRatio = ratio, adjustGeneratedBorder = tonal, tonal = tonal,
    )

    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }

    private fun assertSameOuterAlpha(expected: Bitmap, actual: Bitmap) {
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            assertEquals("fixed outer alpha at $x,$y", Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
        }
    }

    private fun countArtwork(image: Bitmap, background: Int): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image.getPixel(x, y)
            if (Color.alpha(color) == 255 && color != background) count++
        }
        return count
    }

    private fun assertTonalEndpointsPresent(image: Bitmap, foreground: Int, background: Int) {
        val endpoints = GeneratedTonePalette.endpoints(foreground, background)
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        assertTrue("dark source pixels retain the dark palette endpoint", pixels.any { closeColor(it, endpoints.dark) })
        assertTrue("white source pixels retain the light palette endpoint", pixels.any { closeColor(it, endpoints.light) })
    }

    private fun closeColor(actual: Int, expected: Int): Boolean = Color.alpha(actual) == 255 &&
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) <= 1 &&
        kotlin.math.abs(Color.green(actual) - Color.green(expected)) <= 1 &&
        kotlin.math.abs(Color.blue(actual) - Color.blue(expected)) <= 1

    private fun luminance(color: Int): Int =
        (Color.red(color) * 2126 + Color.green(color) * 7152 + Color.blue(color) * 722) / 10000

    private val palettes = listOf(
        Color.rgb(44, 32, 49) to Color.rgb(246, 218, 253),
        Color.rgb(236, 213, 242) to Color.rgb(53, 38, 60),
    )
}
