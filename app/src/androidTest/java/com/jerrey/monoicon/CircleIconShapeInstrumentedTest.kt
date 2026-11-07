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

    @Test
    fun rectangularCircleHasStraightSidesAndSemicircularEnds() {
        for ((width, height) in listOf(480 to 240, 240 to 480)) {
            val content = CircleIconShape.insetBounds(Rect(0, 0, width, height))
            val region = Region().apply {
                setPath(CircleIconShape.clipPath(IconShape.CIRCLE, content)!!, Region(0, 0, width, height))
            }
            // An ellipse misses this point near the straight side of a capsule.
            if (width > height) {
                assertTrue(region.contains(content.left + content.height() / 2 + 8, content.top + 2))
                assertFalse(region.contains(content.left + 2, content.top + 2))
                assertTrue(region.contains(content.left + 2, content.centerY()))
            } else {
                assertTrue(region.contains(content.left + 2, content.top + content.width() / 2 + 8))
                assertFalse(region.contains(content.left + 2, content.top + 2))
                assertTrue(region.contains(content.centerX(), content.top + 2))
            }
        }
    }

    @Test
    fun largeCirclePlateRendersCapsuleSidesWithTransparentPadding() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            for ((width, height) in listOf(480 to 240, 240 to 480)) {
                val drawable = ColoredMonochromeDrawable(mask, 0, PLATE, IconShape.CIRCLE)
                    .forLargeIcon(width, height)
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    drawable.draw(Canvas(out))
                    val content = CircleIconShape.insetBounds(Rect(0, 0, width, height))
                    val x = if (width > height) content.left + content.height() / 2 + 8 else content.left + 2
                    val y = if (width > height) content.top + 2 else content.top + content.width() / 2 + 8
                    assertEquals("straight capsule side", 255, Color.alpha(out.getPixel(x, y)))
                    assertEquals("outside circular end", 0, Color.alpha(out.getPixel(content.left + 2, content.top + 2)))
                    assertEquals("AOSP padding", 0, Color.alpha(out.getPixel(width / 2, 1)))
                } finally {
                    out.recycle()
                }
            }
        } finally {
            mask.recycle()
        }
    }

    @Test
    fun largeGlyphKeepsItsAspectRatioInBothOrientationsAndAfterCloning() {
        val mask = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(64f, 64f, 128f, 128f, Paint().apply { color = Color.WHITE })
        try {
            for (shape in IconShape.entries) {
                for ((width, height) in listOf(480 to 240, 240 to 480, 480 to 480)) {
                    val original = ColoredMonochromeDrawable(mask, GLYPH, 0, shape).forLargeIcon(width, height)
                    val drawable = original.constantState.newDrawable()
                    assertEquals(width, drawable.intrinsicWidth)
                    assertEquals(height, drawable.intrinsicHeight)
                    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        drawable.setBounds(0, 0, width, height)
                        drawable.draw(Canvas(out))
                        var left = width
                        var top = height
                        var right = -1
                        var bottom = -1
                        for (y in 0 until height) for (x in 0 until width) {
                            if (Color.alpha(out.getPixel(x, y)) > 127) {
                                left = minOf(left, x); right = maxOf(right, x)
                                top = minOf(top, y); bottom = maxOf(bottom, y)
                            }
                        }
                        assertTrue("visible glyph", right > left && bottom > top)
                        assertEquals("$shape ${width}x$height square glyph", right - left, bottom - top)
                        assertEquals("centred x", width - 1, left + right)
                        assertEquals("centred y", height - 1, top + bottom)
                    } finally {
                        out.recycle()
                    }
                }
            }
        } finally {
            mask.recycle()
        }
    }

    @Test
    fun largeSquareAndRectanglesUseRoundedPlatesWithCircleDisabled() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            for ((width, height) in listOf(480 to 240, 240 to 480, 480 to 480)) {
                val drawable = ColoredMonochromeDrawable(mask, 0, PLATE, IconShape.SQUIRCLE)
                    .forLargeIcon(width, height)
                val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    drawable.draw(Canvas(out))
                    assertEquals("rounded corner", 0, Color.alpha(out.getPixel(2, 2)))
                    assertEquals("full plate", 255, Color.alpha(out.getPixel(width / 2, 2)))
                    val shortSide = minOf(width, height)
                    assertEquals("ordinary rounded rectangle, not capsule", 255,
                        Color.alpha(out.getPixel(shortSide / 4, 2)))
                } finally {
                    out.recycle()
                }
            }
        } finally {
            mask.recycle()
        }
    }

    @Test
    fun alphaOnlyMasksRetainAntialiasedEdgesWhenTintedAtLargeBounds() {
        val argb = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(argb).drawCircle(64f, 64f, 37.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val alpha = requireNotNull(argb.copy(Bitmap.Config.ALPHA_8, false))
        try {
            for (y in 0 until argb.height) for (x in 0 until argb.width) {
                assertEquals("source coverage at $x,$y", Color.alpha(argb.getPixel(x, y)), Color.alpha(alpha.getPixel(x, y)))
            }
            val images = listOf(argb, alpha).map { mask ->
                Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888).also { out ->
                    ColoredMonochromeDrawable(mask, GLYPH, 0, IconShape.CIRCLE)
                        .forLargeIcon(480, 240).draw(Canvas(out))
                }
            }
            try {
                val pixels = images.map { bitmap -> IntArray(480 * 240).also {
                    bitmap.getPixels(it, 0, 480, 0, 0, 480, 240)
                } }
                var transparent = 0
                var solid = 0
                var fractional = 0
                for (i in pixels[0].indices) {
                    val expected = pixels[0][i]
                    val actual = pixels[1][i]
                    val expectedAlpha = Color.alpha(expected)
                    val actualAlpha = Color.alpha(actual)
                    val location = "${i % 480},${i / 480}"
                    if (expectedAlpha !in 1..254 && actualAlpha !in 1..254) {
                        assertEquals("transparent and solid coverage at $location", expected, actual)
                    } else {
                        // Skia's A8 coverage and RGBA samplers may round an
                        // interpolated edge to adjacent 8-bit values, including
                        // 1/0 and 254/255. One side may therefore reach an
                        // endpoint while the other still has fractional coverage.
                        // Both-endpoint comparisons above remain exact.
                        assertTrue("edge coverage differs by more than one LSB at $location: $expectedAlpha/$actualAlpha",
                            kotlin.math.abs(expectedAlpha - actualAlpha) <= 1)
                    }
                    if (expectedAlpha > 0) assertEquals("ARGB mask tint at $location", GLYPH and 0x00ffffff, expected and 0x00ffffff)
                    if (actualAlpha > 0) assertEquals("alpha-only mask tint at $location", GLYPH and 0x00ffffff, actual and 0x00ffffff)
                    when (actualAlpha) {
                        0 -> transparent++
                        255 -> solid++
                        else -> fractional++
                    }
                }
                assertTrue("transparent exterior remains present", transparent > 0)
                assertTrue("solid glyph interior remains opaque", solid > 0)
                assertTrue("filtered edges retain fractional coverage", fractional > 0)
                images.forEach { bitmap ->
                    assertEquals("glyph centre retains its full tint", GLYPH, bitmap.getPixel(240, 120))
                    for ((x, y) in listOf(0 to 0, 479 to 0, 0 to 239, 479 to 239)) {
                        assertEquals("exterior remains exactly transparent at $x,$y", 0, bitmap.getPixel(x, y))
                    }
                }
            } finally {
                images.forEach(Bitmap::recycle)
            }
        } finally {
            argb.recycle()
            alpha.recycle()
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
