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
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import com.jerrey.monoicon.theme.mask.PixelMonochromeMaskStrategy
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelMaskPipelineInstrumentedTest {

    @After
    fun clearCaches() {
        IconDrawableCache.clear()
        MonochromeCache.shared.clear()
    }

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

    @Test
    fun adaptiveFallbackUsesPixelEqualWeightMask() {
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.BLACK),
            SplitDrawable(64, 64, Color.WHITE),
        )

        val mask = LabMonochromeExtractor.extractPixelAdaptiveIcon(adaptive, 64)

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(8, 32)) > 220)
        assertTrue(Color.alpha(mask.getPixel(56, 32)) < 32)
    }

    @Test
    fun flatRedAdaptiveIconProducesEqualWeightAlpha() {
        val adaptive = AdaptiveIconDrawable(
            ColorDrawable(Color.RED),
            null,
        )

        val mask = LabMonochromeExtractor.extractPixelAdaptiveIcon(adaptive, 64)

        assertNotNull(mask)
        assertTrue(Color.alpha(mask!!.getPixel(32, 32)) in 84..86)
    }

    @Test
    fun midToneCurveMatchesPixelLauncher() {
        assertEquals(0, LabMonochromeExtractor.midToneBoost(0))
        assertEquals(32, LabMonochromeExtractor.midToneBoost(64))
        assertEquals(128, LabMonochromeExtractor.midToneBoost(128))
        assertEquals(223, LabMonochromeExtractor.midToneBoost(192))
        assertEquals(255, LabMonochromeExtractor.midToneBoost(255))
    }

    @Test
    fun legacyWrapperPreservesWideAspectRatio() {
        val wrapped = DrawableConverter.wrapPixelLegacyIcon(
            SizedSolidDrawable(200, 100, Color.BLACK)
        )
        assertNotNull(wrapped)

        val mask = LabMonochromeExtractor.extractPixelAdaptiveIcon(wrapped!!, 128)
        assertNotNull(mask)

        val lowAlphaBounds = findBounds(mask!!) { it < 32 }
        assertNotNull(lowAlphaBounds)
        assertTrue(lowAlphaBounds!!.width() > lowAlphaBounds.height() * 1.7f)
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
        val strategy = PixelMonochromeMaskStrategy().apply {
            configureCache("pixel_test", "instrumented")
        }

        val result = strategy.generate(display, identity)

        assertNotNull(result)
        assertTrue(result!!.rawUsed)
        assertNotNull(result.mask)
    }

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
}
