package com.jerrey.monoicon

import android.content.Context
import android.content.SharedPreferences
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
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.image.DrawableConverter
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.AospMonochromeFactory
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.IconNormalizerCompat
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.mask.MaskStrategy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AOSP geometry and source-aware generated-mask regressions.
 *
 * Generated artwork keeps RGB tone separate from source alpha coverage, with
 * `ClippedMonoDrawable` geometry. Legacy icons retain the normalizer and wrapper
 * geometry. Native/Lawnicons masks and the flat-color AOSP baseline retain their
 * alpha-mask contract; source white artwork must not become transparency.
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
        assertEquals(Bitmap.Config.ALPHA_8, mask!!.config)
        assertTrue(Color.alpha(mask.getPixel(8, 32)) > 220)
        assertTrue(Color.alpha(mask.getPixel(56, 32)) < 32)
    }

    @Test
    fun bothNativePriorityChainsAndLawniconsFallbackPreserveSourceOrder() {
        LawniconsAssetSource.init(InstrumentationRegistry.getInstrumentation().targetContext)
        val identity = "com.tencent.mobileqq"
        val native = AdaptiveIconDrawable(ColorDrawable(Color.WHITE),
            SplitDrawable(64, 64, Color.BLACK), SplitDrawable(64, 64, Color.WHITE))
        val noNative = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), SplitDrawable(64, 64, Color.BLACK))
        fun source(style: IconSourceStyle, icon: Drawable, pkg: String = identity): Int =
            requireNotNull(AospMonochromeMaskStrategy(sourceStyle = style).generate(icon, pkg)).source
        assertEquals(MaskStrategy.SOURCE_NATIVE, source(IconSourceStyle.NATIVE, native))
        assertEquals(MaskStrategy.SOURCE_NATIVE, source(IconSourceStyle.NATIVE_GENERATED, native))
        assertEquals(MaskStrategy.SOURCE_LAWNICONS, source(IconSourceStyle.NATIVE, noNative))
        assertEquals(MaskStrategy.SOURCE_LAWNICONS, source(IconSourceStyle.LAWNICONS, native))
        assertEquals(MaskStrategy.SOURCE_NATIVE, source(IconSourceStyle.LAWNICONS, native, "com.example.missing"))
        val generated = source(IconSourceStyle.GENERATED, native)
        assertTrue(generated != MaskStrategy.SOURCE_NATIVE && generated != MaskStrategy.SOURCE_LAWNICONS)
        assertEquals("native layer cannot change the generated path", generated, source(IconSourceStyle.GENERATED, noNative))
        assertEquals("native priority generates when neither authored resource is available",
            generated, source(IconSourceStyle.NATIVE, noNative, "com.example.missing"))
        assertEquals("direct native priority generates even when the package has a Lawnicons match",
            generated, source(IconSourceStyle.NATIVE_GENERATED, noNative))
        assertEquals("a compatibility flag cannot make direct native priority use Lawnicons",
            generated, requireNotNull(AospMonochromeMaskStrategy(sourceStyle = IconSourceStyle.NATIVE_GENERATED,
                allowLawniconsFallback = true).generate(noNative, identity)).source)
        assertEquals("explicit fallback opt-out skips Lawnicons for the default native chain",
            generated, requireNotNull(AospMonochromeMaskStrategy(sourceStyle = IconSourceStyle.NATIVE,
                allowLawniconsFallback = false).generate(noNative, identity)).source)
    }

    @Test
    fun cacheHitsPreserveTonalGeneratedAndNativeAlphaContracts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artwork = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.BLACK })
        }
        try {
            val generated = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), BitmapDrawable(context.resources, artwork))
            val native = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), BitmapDrawable(context.resources, artwork),
                SplitDrawable(64, 64, Color.WHITE))
            for ((drawable, tonal) in listOf(generated to true, native to false)) {
                val identity = "com.example.tonal-cache/$tonal"
                val strategy = AospMonochromeMaskStrategy().apply { configureCache("tonal_contract", "instrumented") }
                val first = requireNotNull(strategy.generate(drawable, identity))
                val cached = requireNotNull(strategy.generate(drawable, identity))
                assertFalse("first generated entry is new", first.cacheHit)
                assertTrue("repeat generation uses the cache", cached.cacheHit)
                assertEquals(tonal, first.tonal)
                assertEquals("cache does not lose the RGB tone flag", first.tonal, cached.tonal)
                assertEquals(first.source, cached.source)
                val cachedMask = requireNotNull(cached.mask)
                assertEquals(if (tonal) Bitmap.Config.ARGB_8888 else Bitmap.Config.ALPHA_8, cachedMask.config)
                assertTrue(first.mask!!.sameAs(cachedMask))
                if (tonal) {
                    assertTrue("opaque dark motif retains coverage", alphaAt(cachedMask, 0.5f, 0.5f) > 220)
                    assertTrue("tonal cache preserves RGB contrast", toneAt(cachedMask, 0.25f, 0.5f) >
                        toneAt(cachedMask, 0.5f, 0.5f) + 180)
                } else {
                    assertEquals(MaskStrategy.SOURCE_NATIVE, cached.source)
                    assertTrue("native holes are still alpha", alphaAt(cachedMask, 0.875f, 0.5f) < 32)
                }
            }
        } finally { artwork.recycle() }
    }

    // ── AOSP adaptive (no mono) ────────────────────────────────────────

    @Test
    fun placeholderMonochromeFallsBackToRealForeground() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (placeholder in listOf(Color.TRANSPARENT, Color.WHITE)) {
            val adaptive = AdaptiveIconDrawable(
                ColorDrawable(Color.WHITE), SplitDrawable(64, 64, Color.BLACK),
                ColorDrawable(placeholder),
            )
            val result = AospMonochromeMaskStrategy().generate(adaptive, "com.example.placeholder/Main")
            assertNotNull(result)
            assertTrue("placeholder must use a fallback source", result!!.source != MaskStrategy.SOURCE_NATIVE)
            assertTrue("fallback must retain a glyph", AospMonochromeMaskStrategy.hasUsableGlyph(result.mask!!))
        }
    }

    @Test
    fun adaptiveSingleToneForegroundUsesSourceCoverageWithoutItsWrapper() {
        // The opaque background belongs to the wrapper; the black foreground
        // is a single-tone silhouette with its own genuine transparency.
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
        assertEquals(Bitmap.Config.ALPHA_8, mask!!.config)
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // wrapper excluded
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)    // motif
    }

    @Test
    fun unstructuredAdaptiveBackgroundRetainsTheAospFlatColorBaseline() {
        // No foreground or tonal structure exists. This explicitly retained
        // baseline keeps AOSP's min == max behavior (red -> alpha 85).
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
        assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE, result.source)
        assertFalse("a silhouette remains an alpha mask", result.tonal)
        // AdaptiveIconDrawable reports the visible 2/3 viewport as its
        // intrinsic size, not the foreground bitmap's 64-pixel size. Negative
        // inset then enlarges the glyph: (12,12) on a 64-grid is inside it.
        assertTrue(alphaAt(mask, 0.5f, 0.5f) > 220)     // glyph body
        assertTrue(alphaAt(mask, 0.125f, 0.125f) < 32)  // outside the enlarged glyph
        assertTrue(alphaAt(mask, 0.03125f, 0.03125f) < 32)
        val cached = requireNotNull(strategy.generate(display, identity))
        assertTrue(cached.cacheHit)
        assertFalse("cached single-tone foreground remains an alpha silhouette", cached.tonal)
        assertTrue(mask.sameAs(cached.mask))
        IconDrawableCache.remove(identity)
    }

    @Test
    fun adaptiveInsetLayersKeepLightBodyAndDarkDetails() {
        // JMComic3 regression: both layers inset 8.35% (prefill ring), light
        // badge with dark internal details. The badge itself is artwork;
        // neither its light body nor its dark details may be punched out.
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
        assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, result.source)
        assertTrue(result.tonal)
        assertEquals(Bitmap.Config.ARGB_8888, mask.config)
        assertTrue("dark details retain source coverage", alphaAt(mask, 0.5f, 0.5f) > 220)
        assertTrue("light body retains source coverage", alphaAt(mask, 0.1875f, 0.5f) > 220)
        val darkDetail = toneAt(mask, 0.5f, 0.5f)
        val lightBody = toneAt(mask, 0.1875f, 0.5f)
        assertTrue("body and details retain positive RGB tonal contrast", lightBody - darkDetail > 180)
        assertTrue(alphaAt(mask, 0.03125f, 0.03125f) < 32)
        IconDrawableCache.remove(identity)
    }

    @Test
    fun adaptiveOpaqueForegroundPreservesBrightFieldAndDarkMotif() {
        // An opaque bright field belongs to the source. It must retain coverage
        // and remain brighter in RGB than the equally opaque dark motif.
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
        assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, result.source)
        assertTrue(result.tonal)
        assertTrue(alphaAt(mask, 0.5f, 0.5f) > 220)
        assertTrue(alphaAt(mask, 0.1875f, 0.1875f) > 220)
        assertTrue(toneAt(mask, 0.1875f, 0.1875f) - toneAt(mask, 0.5f, 0.5f) > 180)
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
    fun legacyWhiteFieldRetainsCoverageAndPositiveTone() {
        // QQ-class legacy PNG: white source field and dark motif both retain
        // coverage. Only the artificial wrapper outside the source is excluded.
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
        assertTrue(result.tonal)
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // wrap border
        assertTrue(Color.alpha(mask.getPixel(45, 22)) > 220)  // opaque white source field
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // motif body
        assertTrue(Color.red(mask.getPixel(45, 22)) - Color.red(mask.getPixel(32, 32)) > 180)
        IconDrawableCache.remove(identity)
    }

    @Test
    fun coloredPlateLegacyIconRetainsWhiteMotif() {
        // Fenbi-class regression: the wrapper used to invert this white motif
        // into a hole. Source-aware finishing keeps it brighter than the plate.
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
        assertEquals(MaskStrategy.SOURCE_AOSP_LEGACY, result.source)
        assertTrue(result.tonal)
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)       // wrap border
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)    // opaque white artwork
        assertTrue("colored source field retains opaque coverage", Color.alpha(mask.getPixel(45, 22)) > 220)
        assertTrue("white motif is brighter than colored source field in RGB",
            Color.red(mask.getPixel(32, 32)) - Color.red(mask.getPixel(45, 22)) > 180)
        IconDrawableCache.remove(identity)
    }

    @Test
    fun fullBleedBrightArtworkKeepsPositiveToneAndSourceCoverage() {
        // Gallery-class source: wrapper geometry must not reverse the source's
        // bright surroundings and darker motif, or encode either in alpha.
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
        assertTrue(result.tonal)
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)     // wrap border
        assertTrue(Color.alpha(mask.getPixel(45, 22)) > 220)  // bright surround
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // dark motif
        assertTrue(Color.red(mask.getPixel(45, 22)) - Color.red(mask.getPixel(32, 32)) > 180)
        IconDrawableCache.remove(identity)
    }

    @Test
    fun transparentCornerColoredPlateKeepsWhiteBodyAndActualTransparency() {
        // The original transparent corners remain transparent, while an opaque
        // white body and its dark outline remain artwork in the generated mask.
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
        assertTrue(result.tonal)
        assertTrue(Color.alpha(mask.getPixel(12, 12)) < 32)   // transparent corner
        assertTrue(Color.alpha(mask.getPixel(32, 32)) > 220)  // white body stays visible
        val blueAlpha = Color.alpha(mask.getPixel(45, 22))
        assertTrue("opaque blue source field alpha=$blueAlpha", blueAlpha > 220)
        assertTrue("white body stays brighter than blue source field",
            Color.red(mask.getPixel(32, 32)) > Color.red(mask.getPixel(45, 22)) + 80)
        // Locate the outline after IconNormalizer + legacy viewport scaling;
        // the exact pixel depends on the platform's normalizer geometry.
        val outlineTone = (mask.height / 4 until mask.height / 2)
            .map { y -> mask.getPixel(mask.width / 2, y) }
            .filter { Color.alpha(it) > 220 }
            .minOf { Color.red(it) }
        assertTrue("the covered black outline retains dark RGB: $outlineTone", outlineTone < 40)
        IconDrawableCache.remove(identity)
    }

    // ── Compatibility fallback helpers ─────────────────────────────────

    private fun alphaAt(mask: Bitmap, x: Float, y: Float): Int = Color.alpha(
        mask.getPixel(
            (mask.width * x).toInt().coerceIn(0, mask.width - 1),
            (mask.height * y).toInt().coerceIn(0, mask.height - 1),
        ),
    )

    private fun toneAt(mask: Bitmap, x: Float, y: Float): Int = Color.red(
        mask.getPixel(
            (mask.width * x).toInt().coerceIn(0, mask.width - 1),
            (mask.height * y).toInt().coerceIn(0, mask.height - 1),
        ),
    )

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

    // ── Phase 8: Lawnicons bundle tier ─────────────────────────────────

    @Test
    fun lawniconsPureLookupBuildsGlyphMask() {
        // Synthetic bundle: white circle on transparent, alpha = glyph.
        val glyph = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(glyph).drawCircle(32f, 32f, 20f, Paint().apply { color = Color.WHITE })
        val png = java.io.ByteArrayOutputStream().use { out ->
            glyph.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val mask = LawniconsAssetSource.lookupMask(
            identity = "com.example.pack/com.example.pack.Main",
            size = 64,
            index = mapOf("com.example.pack" to "test_glyph"),
            aliases = emptyMap(),
            reader = { java.io.ByteArrayInputStream(png) as java.io.InputStream },
        )

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(32, 32)) > 220) // glyph body
        assertTrue(Color.alpha(mask.getPixel(2, 2)) < 32)      // transparent plate
    }

    @Test
    fun lawniconsAliasAndIndexPrecedence() {
        val index = mapOf(
            "com.example.pack" to "from_package",
            "com.example.pack/com.example.pack.Main" to "from_component",
        )
        val aliases = mapOf("com.example.pack" to "from_alias")

        // alias > component > package
        assertEquals(
            "from_alias",
            LawniconsAssetSource.resolveAsset("com.example.pack/com.example.pack.Main", index, aliases),
        )
        assertEquals(
            "from_component",
            LawniconsAssetSource.resolveAsset("com.example.pack/com.example.pack.Main", index, emptyMap()),
        )
        assertEquals(
            "from_package",
            LawniconsAssetSource.resolveAsset("com.example.pack/com.example.pack.Other", index, emptyMap()),
        )
        // unknown / blank identities never resolve
        assertEquals(null, LawniconsAssetSource.resolveAsset("unknown", index, aliases))
        assertEquals(null, LawniconsAssetSource.resolveAsset("", index, aliases))
        assertEquals(null, LawniconsAssetSource.resolveAsset(null, index, aliases))
        // miss
        assertEquals(null, LawniconsAssetSource.resolveAsset("com.other/x.Y", index, aliases))
    }

    @Test
    fun lawniconsMalformedBundleReturnsNullWithoutThrowing() {
        // Empty index
        assertEquals(
            null,
            LawniconsAssetSource.lookupMask(
                "com.example.pack/com.example.pack.Main", 64,
                emptyMap(), emptyMap(),
            ) { java.io.ByteArrayInputStream(ByteArray(0)) },
        )
        // Reader explodes
        assertEquals(
            null,
            LawniconsAssetSource.lookupMask(
                "com.example.pack/com.example.pack.Main", 64,
                mapOf("com.example.pack" to "boom"), emptyMap(),
            ) { throw IllegalStateException("boom") },
        )
        // Reader returns null (entry missing)
        assertEquals(
            null,
            LawniconsAssetSource.lookupMask(
                "com.example.pack/com.example.pack.Main", 64,
                mapOf("com.example.pack" to "missing"), emptyMap(),
            ) { null },
        )
    }

    @Test
    fun lawniconsNormalizesGlyphToHeuristicTileFraction() {
        // Regression guard for the "enlarged Lawnicons icons" bug.
        // Geometry contract: Lawnicons 215px canvas ÷ MonetIconGenerator
        // 320px icon canvas = 0.6719, matching the heuristic tier's ~56.5%
        // glyph footprint (Lawnicons content maxes out at ~83% of its canvas:
        // 0.833 × 0.6719 ≈ 0.56).
        // Synthetic source: 48/64 = 75% content → expected ≈ 0.75 × 0.6719 × 64 ≈ 32px.
        val glyph = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(glyph).drawRect(8f, 8f, 56f, 56f, Paint().apply { color = Color.WHITE })
        val png = java.io.ByteArrayOutputStream().use { out ->
            glyph.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        val mask = LawniconsAssetSource.lookupMask(
            identity = "com.example.pack/com.example.pack.Main",
            size = 64,
            index = mapOf("com.example.pack" to "framed"),
            aliases = emptyMap(),
            reader = { java.io.ByteArrayInputStream(png) as java.io.InputStream },
        )

        assertNotNull(mask)
        val bounds = findBounds(mask!!) { it > 32 }
        assertNotNull(bounds)
        assertTrue("width=${bounds!!.width()}", bounds.width() in 31..34)
        assertTrue("height=${bounds.height()}", bounds.height() in 31..34)
    }

    @Test
    fun lawniconsRealBundleLookupHitsCoveredPackage() {
        // This build always ships the bundle. Verify its real lookup and reader,
        // not a coordinate left over from the old 192px runtime mask size.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LawniconsAssetSource.init(context)
        val mask = LawniconsAssetSource.lookupMask(
            "com.tencent.mobileqq/com.tencent.mobileqq.activity.SplashActivity",
            192,
        )
        assertNotNull("bundled QQ asset must be available", mask)
        assertEquals(640, mask!!.width)
        assertEquals(640, mask.height)
        // The actual QQ artwork is an outline; its centre is transparent.
        // Check the complete known asset below instead of guessing a solid pixel.
        assertEquals("Lawnicons keeps transparent outer padding", 0, Color.alpha(mask.getPixel(0, 0)))
        assertTrue("the result contains a usable glyph", AospMonochromeMaskStrategy.hasUsableGlyph(mask))
        val expected = LawniconsAssetSource.lookupMask(
            "com.tencent.mobileqq", 640, mapOf("com.tencent.mobileqq" to "qq"), emptyMap(),
        ) { context.assets.open("lawnicons/masks/$it.png") }
        assertNotNull("QQ PNG is packaged independently of the index", expected)
        try {
            assertTrue("real bundle lookup resolves the packaged QQ glyph", mask.sameAs(expected))
        } finally {
            expected?.recycle()
        }
    }

    @Test
    fun selectedLawniconsOverridesNativeMonoAndOptOutRestoresNative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("lawnicons_priority_test", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("lawnicons_enabled", true).commit()
        val names = listOf("remotePrefsProvider", "cachedLawniconsEnabled", "lastLawniconsValue",
            "lastLawniconsRefreshMs", "cachedLawniconsDisabledPackages", "lastLawniconsPackagesRefreshMs")
        val fields = names.associateWith { name ->
            ConfigManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val saved = fields.mapValues { (_, field) ->
            when (val value = field.get(null)) {
                is AtomicLong -> value.get()
                is AtomicBoolean -> value.get()
                else -> value
            }
        }
        try {
            val provider: () -> SharedPreferences? = { prefs }
            fields.getValue("remotePrefsProvider").set(null, provider)
            for (name in listOf("lastLawniconsRefreshMs", "lastLawniconsPackagesRefreshMs")) {
                (fields.getValue(name).get(null) as AtomicLong).set(android.os.SystemClock.elapsedRealtime() - 1_000L)
            }
            LawniconsAssetSource.init(context)
            val pkg = "com.tencent.mobileqq"
            assertTrue(pkg in LawniconsAssetSource.availablePackages())
            assertTrue(LawniconsAssetSource.hasAvailableMask(pkg))
            val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.WHITE),
                SplitDrawable(64, 64, Color.BLACK), SplitDrawable(64, 64, Color.WHITE))
            val strategy = AospMonochromeMaskStrategy()
            val bundled = requireNotNull(strategy.generate(adaptive, pkg))
            assertEquals(MaskStrategy.SOURCE_LAWNICONS, bundled.source)
            assertFalse("author-drawn bundle stays an alpha mask", bundled.tonal)
            val bundledCached = requireNotNull(strategy.generate(adaptive, pkg))
            assertTrue(bundledCached.cacheHit)
            assertFalse("cached bundle does not become generated tonal artwork", bundledCached.tonal)

            prefs.edit().putStringSet("lawnicons_disabled_packages", setOf(pkg)).commit()
            (fields.getValue("lastLawniconsPackagesRefreshMs").get(null) as AtomicLong)
                .set(android.os.SystemClock.elapsedRealtime() - 1_000L)
            val optedOut = strategy.generate(adaptive, pkg)
            assertNotNull(optedOut)
            assertEquals(MaskStrategy.SOURCE_NATIVE, optedOut!!.source)
            assertFalse("native replacement also retains author alpha semantics", optedOut.tonal)
            assertTrue(AospMonochromeMaskStrategy.hasUsableGlyph(optedOut.mask!!))

            prefs.edit().putStringSet("lawnicons_disabled_packages", emptySet()).putBoolean("lawnicons_enabled", false).commit()
            for (name in listOf("lastLawniconsRefreshMs", "lastLawniconsPackagesRefreshMs")) {
                (fields.getValue(name).get(null) as AtomicLong).set(android.os.SystemClock.elapsedRealtime() - 1_000L)
            }
            assertEquals(MaskStrategy.SOURCE_NATIVE, strategy.generate(adaptive, pkg)!!.source)
        } finally {
            fields.forEach { (name, field) ->
                when (val value = field.get(null)) {
                    is AtomicLong -> value.set(saved.getValue(name) as Long)
                    is AtomicBoolean -> value.set(saved.getValue(name) as Boolean)
                    else -> field.set(null, saved[name])
                }
            }
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun lawniconsMissFallsBackToAospTier() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = "com.example.notinpack/whatever.Main"
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(fg).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.BLACK })
        IconDrawableCache.put(
            identity,
            AdaptiveIconDrawable(ColorDrawable(Color.WHITE), BitmapDrawable(context.resources, fg)),
        )
        val display = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), ColorDrawable(Color.WHITE))
        val strategy = AospMonochromeMaskStrategy().apply {
            configureCache("aosp_test", "instrumented")
        }

        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertNotNull(result!!.mask)
        assertTrue(result.source != 7) // never the Lawnicons tier for an uncovered package
        IconDrawableCache.remove(identity)
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
