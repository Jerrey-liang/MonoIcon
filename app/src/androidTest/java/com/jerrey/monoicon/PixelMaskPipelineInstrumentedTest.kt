package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.AospMonochromeFactory
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.IconNormalizerCompat
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AOSP android15-release mask pipeline tests (Phase 7).
 *
 * The strict branches follow `MonochromeIconFactory` (black prefill,
 * equal-weight grayscale, min/max stretch, EDGE-STRIP polarity flip, no
 * mid-tone boost) + `ClippedMonoDrawable` geometry. Legacy icons are
 * wrapped with `IconNormalizer.getScale() * 0.4667` on a white plate.
 *
 * Known AOSP limitation covered by these tests: a colored plate with a
 * WHITE motif (fenbi-class) has the motif flipped to transparency.
 */
@RunWith(AndroidJUnit4::class)
class PixelMaskPipelineInstrumentedTest {

    @After
    fun clearCaches() {
        IconDrawableCache.clear()
        MonochromeCache.shared.clear()
        PixelMonetColorEngine.invalidate()
    }

    // ── Native monochrome ──────────────────────────────────────────────

    @Test
    fun nativeMonochromePreservesAlphaShape() {
        val mask = LabMonochromeExtractor.extractPixelNativeMonochrome(
            SplitDrawable(64, 64, Color.WHITE),
            64,
        )

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(8, 32)) > 220)
        assertTrue(Color.alpha(mask.getPixel(56, 32)) < 32)
    }

    // ── AOSP adaptive (no mono) ────────────────────────────────────────

    @Test
    fun adaptiveWithoutMonoUsesAospEdgeFlip() {
        // White adaptive background + dark motif: the white edge strips must
        // flip the polarity so the plate goes transparent and the motif is
        // opaque.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(fg).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.BLACK })
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(context.resources, fg),
        )

        val mask = AospMonochromeFactory.wrap(adaptive, 64)

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(2, 2)) < 32)     // plate
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)    // motif
    }

    @Test
    fun flatRedAdaptiveIconPassesThroughGrayscale() {
        // min == max: AOSP leaves the grayscale bytes unchanged (red → 85).
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.RED),
            null,
        )

        val mask = AospMonochromeFactory.wrap(adaptive, 64)

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(32, 32)) in 84..86)
    }

    @Test
    fun adaptiveNearFlatGlyphUsesSilhouette() {
        // Near-flat single-color glyph on a transparent foreground: the
        // silhouette branch keeps the glyph solid instead of flipping it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/adaptive-flat-glyph"
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(fg).drawCircle(32f, 32f, 20f, Paint().apply { color = Color.WHITE })
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(context.resources, fg),
        )
        IconDrawableCache.put(identity, adaptive)

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // glyph body
        assertTrue(Color.alpha(mask.getPixel(12, 12)) < 32)   // plate
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // corner
        IconDrawableCache.remove(identity)
    }

    @Test
    fun adaptiveInsetLayersFollowVisibleRingFlip() {
        // JMComic3 regression: both layers inset 8.35% (prefill ring), light
        // badge with dark internal details. The visible-ring flip must make
        // the dark details the glyph while the plate and the badge body stay
        // transparent.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/adaptive-inset-layers"
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        val fgCanvas = Canvas(fg)
        fgCanvas.drawCircle(32f, 32f, 30f, Paint().apply { color = Color.rgb(210, 210, 210) })
        fgCanvas.drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.rgb(20, 20, 20) })
        val adaptive = AdaptiveIconDrawable(
            android.graphics.drawable.InsetDrawable(ColorDrawable(Color.WHITE), 0.0835f),
            android.graphics.drawable.InsetDrawable(
                BitmapDrawable(context.resources, fg), 0.0835f,
            ),
        )
        IconDrawableCache.put(identity, adaptive)

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 200)  // dark details
        assertTrue(Color.alpha(mask.getPixel(12, 32)) < 64)   // badge body (haze-cleared)
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // plate corner
        IconDrawableCache.remove(identity)
    }

    @Test
    fun adaptiveOpaqueForegroundFallsBackToVisibleRingFlip() {
        // Opaque full-cell foreground (bright art + dark motif): no
        // transparent structure, so the B core applies and the dark motif
        // becomes the glyph.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/adaptive-opaque-fg"
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(230, 230, 230))
        }
        Canvas(fg).drawRect(24f, 24f, 40f, 40f, Paint().apply {
            color = Color.rgb(40, 40, 40)
        })
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(context.resources, fg),
        )
        IconDrawableCache.put(identity, adaptive)

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // dark motif
        assertTrue(Color.alpha(mask.getPixel(12, 12)) < 64)   // bright art (haze-cleared)
        IconDrawableCache.remove(identity)
    }

    // ── AOSP edge-flip predicate ───────────────────────────────────────

    @Test
    fun aospEdgeFlipDecision() {
        // White edge over a black motif: edgeMapped = 1.0 → flip.
        assertTrue(AospMonochromeFactory.edgeMapped(255.0, 0, 255) > 0.5)
        // Dark edge over a light motif: edgeMapped ≈ 0.078 → no flip.
        assertTrue(AospMonochromeFactory.edgeMapped(20.0, 0, 255) < 0.5)
        // Flat image never flips.
        assertEquals(0.0, AospMonochromeFactory.edgeMapped(100.0, 100, 100), 0.001)
    }

    // ── AOSP IconNormalizer ────────────────────────────────────────────

    @Test
    fun aospNormalizerSquareOpaqueIcon() {
        // Full-square opaque content → sqrt(375/576) ≈ 0.8069.
        val scale = IconNormalizerCompat.getScale(SizedSolidDrawable(64, 64, Color.BLACK), 64)
        assertEquals(0.8069f, scale, 0.001f)
    }

    @Test
    fun aospNormalizerWideAspectRatioIcon() {
        // Full opaque 2:1 content → same square-area scale.
        val scale = IconNormalizerCompat.getScale(SizedSolidDrawable(200, 100, Color.BLACK), 128)
        assertEquals(0.8069f, scale, 0.001f)
    }

    @Test
    fun aospNormalizerDiamondIconKeepsScale() {
        // A diamond hull is rounder than a circle (0.5 < π/4) but its area
        // (50%) is below MAX_CIRCLE_AREA_FACTOR → scale stays 1.
        val scale = IconNormalizerCompat.getScale(DiamondDrawable(64, 64), 64)
        assertEquals(1f, scale, 0.001f)
    }

    // ── AOSP legacy wrap + mask ────────────────────────────────────────

    @Test
    fun legacyWrapperPreservesWideAspectRatio() {
        val wrapped = DrawableConverter.wrapAospLegacyIcon(
            SizedSolidDrawable(200, 100, Color.BLACK),
            128,
        )
        assertNotNull(wrapped)

        val mask = AospMonochromeFactory.wrap(wrapped!!, 128)
        assertNotNull(mask)

        val opaqueBounds = findBounds(mask!!) { it > 32 }
        assertNotNull(opaqueBounds)
        assertTrue(opaqueBounds!!.width() > opaqueBounds.height() * 1.7f)
    }

    @Test
    fun rawBitmapCacheTakesPriorityOverDisplayDrawable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/raw-cache"
        val raw = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        IconDrawableCache.put(identity, BitmapDrawable(context.resources, raw))

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }

        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
    }

    @Test
    fun legacyWhitePlateIconExcludesPlateFromMask() {
        // QQ-class legacy PNG: white plate + dark motif. The AOSP edge flip
        // must exclude the plate and keep the dark motif opaque.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/legacy-white-plate"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        Canvas(bitmap).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.BLACK })
        IconDrawableCache.put(identity, BitmapDrawable(context.resources, bitmap))

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // wrap border
        assertTrue(Color.alpha(mask.getPixel(45, 22)) < 32)   // white plate
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // motif body
        IconDrawableCache.remove(identity)
    }

    @Test
    fun coloredPlateLegacyIconFollowsAospEdgeFlip() {
        // fenbi-class: solid blue plate + WHITE motif. Faithful AOSP: the
        // white motif shares the plate's gray value, so the flip punches it
        // out (hole), and the mid-gray blue plate keeps a mid alpha.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/legacy-colored-plate"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0, 128, 224))
        }
        Canvas(bitmap).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.WHITE })
        IconDrawableCache.put(identity, BitmapDrawable(context.resources, bitmap))

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)       // wrap border
        assertTrue(Color.alpha(mask.getPixel(32, 32)) < 32)     // white motif → HOLE (AOSP)
        val blueAlpha = Color.alpha(mask.getPixel(45, 22))
        assertTrue("blue plate alpha=$blueAlpha", blueAlpha in 90..190)
        IconDrawableCache.remove(identity)
    }

    @Test
    fun fullBleedBrightArtworkFollowsAospEdgeFlip() {
        // gallery-class: bright full-bleed artwork + darker motif. The white
        // wrap edge triggers the flip: bright surround → dark, motif → opaque.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/legacy-full-bleed"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(200, 200, 232))
        }
        Canvas(bitmap).drawRect(28f, 28f, 36f, 36f, Paint().apply {
            color = Color.rgb(64, 64, 112)
        })
        IconDrawableCache.put(identity, BitmapDrawable(context.resources, bitmap))

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // wrap border
        assertTrue(Color.alpha(mask.getPixel(45, 22)) < 100)  // bright surround → dark
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // dark motif
        IconDrawableCache.remove(identity)
    }

    @Test
    fun transparentCornerColoredPlateFollowsAospEdgeFlip() {
        // fenbi-class with transparent rounded corners: white corner spill
        // and the white motif body are both flipped to transparency, the
        // blue plate keeps mid alpha, the black outline stays opaque.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "test.pixel/legacy-transparent-corners"
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(
            8f, 8f, 56f, 56f, 12f, 12f,
            Paint().apply { color = Color.rgb(0, 128, 224) },
        )
        canvas.drawCircle(32f, 32f, 10f, Paint().apply { color = Color.WHITE })
        canvas.drawCircle(
            32f, 32f, 10f,
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.BLACK
            },
        )
        IconDrawableCache.put(identity, BitmapDrawable(context.resources, bitmap))

        val display = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            ColorDrawable(Color.WHITE),
        )
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }
        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
        val mask = result.mask!!
        assertTrue(Color.alpha(mask.getPixel(12, 12)) < 32)   // transparent corner
        assertTrue(Color.alpha(mask.getPixel(32, 32)) < 32)   // white body → HOLE (AOSP)
        val blueAlpha = Color.alpha(mask.getPixel(45, 22))
        assertTrue("blue plate alpha=$blueAlpha", blueAlpha in 90..190)
        assertTrue(Color.alpha(mask.getPixel(32, 27)) > 180)  // black outline ring
        IconDrawableCache.remove(identity)
    }

    // ── Compatibility fallback helpers ─────────────────────────────────

    @Test
    fun midToneCurveMatchesPixelLauncher() {
        assertEquals(0, LabMonochromeExtractor.midToneBoost(0))
        assertEquals(32, LabMonochromeExtractor.midToneBoost(64))
        assertEquals(128, LabMonochromeExtractor.midToneBoost(128))
        assertEquals(223, LabMonochromeExtractor.midToneBoost(192))
        assertEquals(255, LabMonochromeExtractor.midToneBoost(255))
    }

    // ── AOSP themed-icon color contract ────────────────────────────────

    @Test
    fun aospIconColorsMatchTonedContract() {
        // iconloaderlib values-v31: fg=accent1_700(30)/bg=accent1_100(90);
        // values-night-v31: fg=accent1_200(80)/bg=accent2_800(20).
        val seed = 0xFFA592D3.toInt()
        val light = Material2025ColorEngine.iconColorsForSource(seed, dark = false)
        val dark = Material2025ColorEngine.iconColorsForSource(seed, dark = true)

        assertEquals(30.0, Hct.fromInt(light.foreground).tone, 1.0)
        assertEquals(90.0, Hct.fromInt(light.background).tone, 1.0)
        assertEquals(80.0, Hct.fromInt(dark.foreground).tone, 1.0)
        assertEquals(20.0, Hct.fromInt(dark.background).tone, 1.0)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun findBounds(bitmap: Bitmap, predicate: (Int) -> Boolean): Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!predicate(Color.alpha(bitmap.getPixel(x, y)))) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right >= left && bottom >= top) {
            Rect(left, top, right + 1, bottom + 1)
        } else {
            null
        }
    }

    private class SplitDrawable(
        private val width: Int,
        private val height: Int,
        private val color: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@SplitDrawable.color }

        override fun draw(canvas: Canvas) {
            canvas.drawRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.exactCenterX(),
                bounds.bottom.toFloat(),
                paint,
            )
        }

        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class SizedSolidDrawable(
        private val width: Int,
        private val height: Int,
        private val color: Int,
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawColor(color)
        }

        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private class DiamondDrawable(
        private val width: Int,
        private val height: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        override fun draw(canvas: Canvas) {
            val path = android.graphics.Path().apply {
                moveTo(bounds.exactCenterX(), bounds.top.toFloat())
                lineTo(bounds.right.toFloat(), bounds.exactCenterY())
                lineTo(bounds.exactCenterX(), bounds.bottom.toFloat())
                lineTo(bounds.left.toFloat(), bounds.exactCenterY())
                close()
            }
            canvas.drawPath(path, paint)
        }

        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
