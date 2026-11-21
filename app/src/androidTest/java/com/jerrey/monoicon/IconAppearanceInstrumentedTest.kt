package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.theme.mask.MiuiIconShapeCompat
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.IconAppearance
import com.jerrey.monoicon.theme.render.IconShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconAppearanceInstrumentedTest {
    @Test fun intermediateMasksChangeCornersWithoutChangingFullBleedAnimationBounds() {
        val masks = listOf(0, 50, 100).map { percent ->
            val path = requireNotNull(PathParser.createPathFromPathData(IconAppearance(percent, 100).maskPathData()))
            val bounds = RectF().also { path.computeBounds(it, true) }
            assertEquals(RectF(0f, 0f, 100f, 100f), bounds)
            assertTrue(bounds.contains(50f, 0f))
            Region().apply { setPath(path, Region(0, 0, 100, 100)) }
        }
        assertTrue(masks[0].contains(20, 2))
        assertFalse(masks[1].contains(20, 2))
        assertTrue(masks[1].contains(32, 2))
        assertFalse(masks[2].contains(32, 2))
    }

    @Test fun circleAtExistingSizeKeepsPreviousDiameterAndSizeSliderEnlargesTheWholeIcon() {
        val mask = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(64f, 64f, 128f, 128f, Paint().apply { color = Color.WHITE })
        val legacy = render(ColoredMonochromeDrawable(mask, 0, Color.RED, IconShape.CIRCLE), 384, 384)
        val images = listOf(0, 50, 100).map { size ->
            render(ColoredMonochromeDrawable(mask, 0, Color.RED, 100, size, false), 384, 384)
        }
        try {
            // Direct plates antialias their outline while the legacy adaptive
            // composite clips it. Their normalized diameter and colors must agree.
            val legacyWidth = (0 until legacy.width).count { Color.alpha(legacy.getPixel(it, legacy.height / 2)) > 127 }
            val widths = images.map { image ->
                (0 until image.width).count { Color.alpha(image.getPixel(it, image.height / 2)) > 127 }
            }
            assertEquals("legacy circle diameter", legacyWidth, widths[0])
            assertEquals("legacy plate color", legacy.getPixel(192, 192), images[0].getPixel(192, 192))
            assertEquals("legacy transparent padding", 0, Color.alpha(images[0].getPixel(192, 1)))
            assertTrue("size increases continuously: $widths", widths[0] < widths[1] && widths[1] < widths[2])
            assertEquals("system tile size", 384, widths[2])
        } finally {
            legacy.recycle(); images.forEach(Bitmap::recycle); mask.recycle()
        }
    }

    @Test fun squareAppearanceOverridesFrameworkMaskAndReusedBoundsKeepTheRequestedRadius() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val images = listOf(0, 50, 100).map { percent ->
                render(ColoredMonochromeDrawable(mask, 0, Color.RED, percent, 100, false), 200, 200)
            }
            try {
                assertEquals("rounded rectangle corner area", 255, Color.alpha(images[0].getPixel(40, 4)))
                assertEquals("intermediate excludes the same area", 0, Color.alpha(images[1].getPixel(40, 4)))
                assertEquals("intermediate retains its straight top edge", 255, Color.alpha(images[1].getPixel(64, 4)))
                assertEquals("circle excludes the former top edge", 0, Color.alpha(images[2].getPixel(64, 4)))
            } finally { images.forEach(Bitmap::recycle) }
            val reused = ColoredMonochromeDrawable(mask, 0, Color.RED, 37, 52, false)
            for ((width, height) in listOf(200 to 200, 480 to 240, 240 to 480, 200 to 200)) {
                val actual = render(reused, width, height)
                val expected = render(ColoredMonochromeDrawable(mask, 0, Color.RED, 37, 52, false), width, height)
                try { assertTrue("geometry after ${width}x$height", actual.sameAs(expected)) }
                finally { actual.recycle(); expected.recycle() }
            }
        } finally { mask.recycle() }
    }

    @Test fun circleEndpointKeepsCapsuleEndsInBothOrientationsAndAtEverySize() {
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            for ((width, height) in listOf(480 to 240, 240 to 480)) for (size in listOf(0, 50, 100)) {
                val appearance = IconAppearance(100, size)
                val content = appearance.contentBounds(Rect(0, 0, width, height))
                val image = render(ColoredMonochromeDrawable(mask, 0, Color.RED, 100, size, false)
                    .forLargeIcon(width, height), width, height)
                try {
                    val x = if (width > height) content.left + content.height() / 2 + 8 else content.left + 2
                    val y = if (width > height) content.top + 2 else content.top + content.width() / 2 + 8
                    assertEquals("straight capsule side", 255, Color.alpha(image.getPixel(x, y)))
                    assertEquals("semicircular end", 0, Color.alpha(image.getPixel(content.left + 2, content.top + 2)))
                } finally { image.recycle() }
            }
        } finally { mask.recycle() }
    }

    @Test fun cloneRetainsIntermediateCornersSizeAndFixedPreviewPalette() {
        val mask = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(64f, 64f, 128f, 128f, Paint().apply { color = Color.WHITE })
        val drawable = ColoredMonochromeDrawable(mask, Color.BLUE, Color.RED, 46, 68, false)
            .forLargeIcon(480, 240)
        val original = render(drawable, 480, 240)
        val clone = render(drawable.constantState.newDrawable(), 480, 240)
        try {
            assertTrue("clone keeps complete geometry", original.sameAs(clone))
            assertEquals("supplied glyph color", Color.BLUE, clone.getPixel(240, 120))
            assertEquals("supplied plate color", Color.RED, clone.getPixel(240, 40))
        } finally { original.recycle(); clone.recycle(); mask.recycle() }
    }

    @Test fun miuiBitmapCacheIncludesIntermediateRadius() {
        val rounded = requireNotNull(MiuiIconShapeCompat.maskCopy(200, 0))
        val circle = requireNotNull(MiuiIconShapeCompat.maskCopy(200, 100))
        try {
            assertTrue(Color.alpha(rounded.getPixel(40, 4)) > 127)
            assertEquals(0, Color.alpha(circle.getPixel(40, 4)))
        } finally { rounded.recycle(); circle.recycle() }
    }

    private fun render(drawable: android.graphics.drawable.Drawable, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }
}
