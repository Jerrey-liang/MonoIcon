package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.theme.mask.MiuiIconShapeCompat
import com.jerrey.monoicon.theme.render.CircleIconShape
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Circle icon shape tests (Phase 9).
 *
 * Covers the two halves of the feature:
 * - geometry — the MIUI-facing mask path must stay full-bleed (it keeps
 *   MIUI's `mIsFullMask` predicate true) and round;
 * - rendering — [ColoredMonochromeDrawable] with [IconShape.CIRCLE] clips to
 *   that circle while [IconShape.SQUIRCLE] stays untouched;
 * - MIUI bridge — `mConfigIconMask` injection works and every entry point is
 *   failure-tolerant.
 */
@RunWith(AndroidJUnit4::class)
class CircleIconShapeInstrumentedTest {

    private val size = 384

    @Before
    fun checkGeometry() {
        assertTrue(
            "circle mask geometry self-check failed",
            MiuiIconShapeCompat.selfCheck(),
        )
        assertFalse(MiuiIconShapeCompat.isBypassed())
    }

    // ── Geometry ───────────────────────────────────────────────────────

    @Test
    fun circleMaskPathIsFullBleed() {
        val path = PathParser.createPathFromPathData(CircleIconShape.MASK_PATH_DATA)
        val bounds = RectF()
        path.computeBounds(bounds, true)

        assertTrue("bounds=$bounds", bounds.left <= 0.5f && bounds.top <= 0.5f)
        assertTrue("bounds=$bounds", bounds.right >= 99.5f && bounds.bottom >= 99.5f)
        // MIUI: mIsFullMask = maskPath.computeBounds(rect).contains(50, 0)
        assertTrue("RectF.contains(50,0) must stay true", bounds.contains(50f, 0f))
    }

    @Test
    fun circleMaskPathIsCircular() {
        val path: Path = PathParser.createPathFromPathData(CircleIconShape.MASK_PATH_DATA)
        val region = Region()
        region.setPath(path, Region(0, 0, 100, 100))

        assertTrue("center", region.contains(50, 50))
        assertTrue("top", region.contains(50, 1))
        assertTrue("left", region.contains(1, 50))
        assertTrue("right", region.contains(99, 50))
        assertTrue("bottom", region.contains(50, 99))
        assertFalse("corner inside → square, not circle", region.contains(3, 3))
        assertFalse("corner inside → square, not circle", region.contains(96, 96))
    }

    @Test
    fun squareShapeProducesNoClip() {
        assertNull(CircleIconShape.clipPath(IconShape.SQUIRCLE, Rect(0, 0, size, size)))
        assertNull(CircleIconShape.clipPath(IconShape.CIRCLE, Rect(0, 0, 0, 0)))
    }

    // ── MIUI config bridge ────────────────────────────────────────────

    @Test
    fun injectMaskWritesFieldOnStub() {
        val stub = StubIconConfig()
        assertTrue(MiuiIconShapeCompat.injectMask(stub))
        assertEquals(CircleIconShape.MASK_PATH_DATA, stub.mConfigIconMask)
        // Idempotent
        assertTrue(MiuiIconShapeCompat.injectMask(stub))
        // Never throws on junk input
        assertFalse(MiuiIconShapeCompat.injectMask(null))
        assertFalse(MiuiIconShapeCompat.injectMask("not a config"))
    }

    @Test
    fun themeMaskNameDetection() {
        assertTrue(MiuiIconShapeCompat.isThemeMaskName("icon_mask.png"))
        assertFalse(MiuiIconShapeCompat.isThemeMaskName("icon_background.png"))
        assertFalse(MiuiIconShapeCompat.isThemeMaskName(null))
    }

    @Test
    fun circleMaskCopyIsCircularAndFresh() {
        val first = MiuiIconShapeCompat.circleMaskCopy(192)
        val second = MiuiIconShapeCompat.circleMaskCopy(192)
        assertTrue("bitmap available", first != null && second != null)
        assertNotSame("each caller gets its own copy", first, second)

        val bitmap = first!!
        assertEquals(192, bitmap.width)
        assertTrue("center filled", Color.alpha(bitmap.getPixel(96, 96)) > 0)
        assertEquals("corner transparent", 0, Color.alpha(bitmap.getPixel(2, 2)))
        assertEquals(
            "outside circle (dx=dy=86 → r=121 > 96)",
            0,
            Color.alpha(bitmap.getPixel(10, 10)),
        )
    }

    @Test
    fun miuiCacheInvalidationIsFailureSafe() {
        // Must simply not throw; returns true when IconCustomizer is present.
        try {
            MiuiIconShapeCompat.invalidateMiuiCaches()
        } catch (t: Throwable) {
            throw AssertionError("invalidateMiuiCaches threw: ${t.message}", t)
        }
    }

    @Test
    fun miuiRealClassAcceptsInjectedMask() {
        val clazz = try {
            Class.forName("miui.content.res.IconCustomizer")
        } catch (_: Throwable) {
            null
        }
        org.junit.Assume.assumeTrue("MIUI IconCustomizer not available", clazz != null)

        val config = try {
            clazz!!.getDeclaredMethod("ensureIconConfigLoaded")
                .apply { isAccessible = true }
                .invoke(null)
        } catch (_: Throwable) {
            null
        }
        org.junit.Assume.assumeTrue("IconConfig unavailable in this process", config != null)

        assertTrue(MiuiIconShapeCompat.injectMask(config))
        val field = config!!.javaClass.getDeclaredField("mConfigIconMask")
            .apply { isAccessible = true }
        assertEquals(CircleIconShape.MASK_PATH_DATA, field.get(config))
    }

    // ── Renderer ──────────────────────────────────────────────────────

    @Test
    fun aospNormalizationScaleMatchesIconNormalizer() {
        // IconNormalizer.normalizeAdaptiveIcon() → sqrt(375/576 / maskArea).
        // The area is Region-measured (AOSP GraphicsUtils.getArea), which for
        // this 4-arc path is ≈0.781 rather than the analytic π/4 = 0.7854.
        val fraction = CircleIconShape.areaFraction()
        assertTrue("area fraction=$fraction", fraction in 0.775f..0.790f)

        val scale = CircleIconShape.aospScale()
        assertTrue("scale=$scale", scale in 0.905f..0.918f)
        assertEquals(
            "scale must equal sqrt(MAX_SQUARE_AREA_FACTOR / area)",
            kotlin.math.sqrt((375f / 576f) / fraction),
            scale,
            1e-4f,
        )

        // BaseIconFactory.drawIconBitmap(): offset = max(blur, size*(1-scale)/2)
        val inset = CircleIconShape.insetBounds(Rect(0, 0, size, size))
        val expectedOffset = Math.round(size * (1f - scale) / 2f)
        assertEquals(expectedOffset, inset.left)
        assertEquals(expectedOffset, inset.top)
        assertEquals(size - expectedOffset, inset.right)
        assertEquals(size - expectedOffset, inset.bottom)
        // 1.68/48 = 3.5% blur room is smaller than the ~4.3% scale offset here.
        assertTrue("scale offset must dominate", expectedOffset > kotlin.math.ceil(size * 1.68f / 48f))
    }

    @Test
    fun moduleDrawableCircleIsInsetWithTransparentPadding() {
        val bitmap = render(IconShape.CIRCLE)

        // Visible diameter ≈ scale × size, centred (transparent ring around it).
        val row = size / 2
        var first = -1
        var last = -1
        for (x in 0 until size) {
            if (Color.alpha(bitmap.getPixel(x, row)) > 0) {
                if (first < 0) first = x
                last = x
            }
        }
        assertTrue("visible icon found on the centre row", first >= 0 && last > first)
        val diameter = (last - first + 1).toFloat() / size
        assertEquals("AOSP scale diameter", CircleIconShape.aospScale(), diameter, 0.02f)

        // The ring is transparent — never filled with the plate colour.
        assertEquals("top padding", 0, Color.alpha(bitmap.getPixel(size / 2, 3)))
        assertEquals("bottom padding", 0, Color.alpha(bitmap.getPixel(size / 2, size - 4)))
        assertEquals("left padding", 0, Color.alpha(bitmap.getPixel(3, size / 2)))
        assertEquals("right padding", 0, Color.alpha(bitmap.getPixel(size - 4, size / 2)))
        assertEquals("corner padding", 0, Color.alpha(bitmap.getPixel(4, 4)))

        // Inside the (smaller) circle: plate near the top, glyph at the centre.
        val inset = CircleIconShape.insetBounds(Rect(0, 0, size, size))
        val plate = bitmap.getPixel(size / 2, inset.top + 6)
        val glyph = bitmap.getPixel(size / 2, size / 2)
        assertTrue("plate visible just inside the circle", Color.alpha(plate) > 0)
        assertTrue("glyph visible at the centre", Color.alpha(glyph) > 0)
        assertNotEquals("glyph differs from plate", plate, glyph)
        // 40 px from the corner lies outside the inset circle (r ≈ 174.8).
        assertEquals(
            "outside the inset circle",
            0,
            Color.alpha(bitmap.getPixel(40, 40)),
        )
    }

    @Test
    fun moduleDrawableSquircleUnchanged() {
        val bitmap = render(IconShape.SQUIRCLE)

        assertNull(
            "no clip path is produced without the circle shape",
            CircleIconShape.clipPath(IconShape.SQUIRCLE, Rect(0, 0, size, size)),
        )
        assertTrue("plate visible near the top edge", Color.alpha(bitmap.getPixel(size / 2, 20)) > 0)
        assertTrue("glyph visible at the centre", Color.alpha(bitmap.getPixel(size / 2, size / 2)) > 0)
        // Squircle keeps the previous full-bleed behaviour: no AOSP padding.
        assertTrue(
            "no padding without the circle shape",
            Color.alpha(bitmap.getPixel(size / 2, 3)) > 0,
        )
        // HyperOS's framework config_icon_mask is a full square, so without our
        // clip this squircle-corner sample stays inside the launcher mask.
        assertTrue(
            "corner sample untouched by MonoIcon",
            Color.alpha(bitmap.getPixel(40, 40)) > 0,
        )
    }

    @Test
    fun reusedDrawableGeometryMatchesFreshRenderingAfterBoundsChanges() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val positions = listOf(
            Rect(0, 0, size, size),
            Rect(0, 0, size, size),
            Rect(24, 40, 240, 296),
            Rect(72, 32, 328, 192),
            Rect(0, 0, size, size),
        )
        try {
            IconShape.entries.forEach { shape ->
                // A transparent plate keeps this geometry regression independent
                // of live wallpaper/dynamic-colour changes in the test process.
                val reused = ColoredMonochromeDrawable(mask, GLYPH, 0, shape)
                positions.forEach { bounds ->
                    val actual = renderAt(reused, bounds)
                    val expected = renderAt(ColoredMonochromeDrawable(mask, GLYPH, 0, shape), bounds)
                    try {
                        assertTrue("$shape bounds=$bounds", actual.sameAs(expected))
                    } finally {
                        actual.recycle()
                        expected.recycle()
                    }
                }
            }
        } finally {
            mask.recycle()
        }
    }

    private fun renderAt(drawable: ColoredMonochromeDrawable, bounds: Rect): Bitmap =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.bounds = bounds
            drawable.draw(Canvas(bitmap))
        }

    /** Renders the drawable at [size]² with a 1/3-sized glyph in the middle. */
    private fun render(shape: IconShape): Bitmap {
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(
            size / 3f,
            size / 3f,
            size * 2f / 3f,
            size * 2f / 3f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK },
        )
        val drawable = ColoredMonochromeDrawable(mask, GLYPH, PLATE, shape)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(out))
        return out
    }

    /** Mirrors `IconCustomizer$IconConfig`'s field name for reflection tests. */
    private class StubIconConfig {
        @JvmField
        var mConfigIconMask: String? = null
    }

    private companion object {
        const val PLATE = Color.RED
        const val GLYPH = Color.BLUE
    }
}
