package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.res.Resources
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.theme.mask.AospMonochromeFactory
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.LabMonochromeExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedMaskInstrumentedTest {
    @Test
    fun artworkInBackgroundLayerKeepsWhiteMotifWithEmptyOrMissingForeground() {
        val resources = Resources.getSystem()
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            // Bright colored surroundings and dark ink trigger the old edge
            // inversion, even though the opaque white center is real artwork.
            eraseColor(Color.rgb(128, 200, 255))
            Canvas(this).drawRect(22f, 22f, 42f, 42f, Paint().apply { color = Color.BLACK })
            Canvas(this).drawRect(25f, 25f, 39f, 39f, Paint().apply { color = Color.WHITE })
        }
        try {
            for (foreground in listOf<Drawable?>(null, ColorDrawable(Color.TRANSPARENT))) {
                for (ratio in listOf(0f, 1f)) {
                    val adaptive = AdaptiveIconDrawable(BitmapDrawable(resources, original), foreground)
                    val mask = AospMonochromeFactory.wrap(adaptive, 64, borderRatio = ratio)
                    assertNotNull(mask)
                    try {
                        assertEquals(Bitmap.Config.ARGB_8888, mask!!.config)
                        assertTrue("white artwork in the background must stay opaque", Color.alpha(mask.getPixel(32, 32)) > 220)
                        assertTrue("ink surrounding white artwork retains coverage", Color.alpha(mask.getPixel(19, 32)) > 220)
                        assertTrue("white and ink differ in tone, not coverage",
                            Color.red(mask.getPixel(32, 32)) - Color.red(mask.getPixel(19, 32)) > 180)
                    } finally {
                        mask?.recycle()
                    }
                }
            }
        } finally {
            original.recycle()
        }
    }

    @Test
    fun explicitOuterBorderSettingSeparatesCacheEntriesWithoutChangingLegacyArtwork() {
        val resources = Resources.getSystem()
        val original = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0, 128, 224))
            Canvas(this).drawRect(24f, 24f, 40f, 40f, Paint().apply { color = Color.WHITE })
        }
        val drawable = BitmapDrawable(resources, original)
        val identity = "com.example.generated-border/Main"
        IconDrawableCache.put(identity, drawable)
        MonochromeCache.shared.clear()
        try {
            val masks = listOf(0f, 0.5f, 1f).map { ratio ->
                AospMonochromeMaskStrategy(generatedBorderRatio = ratio).generate(drawable, identity)!!.also {
                    assertTrue("first use of each setting has an independent cache entry", !it.cacheHit)
                    assertTrue("generated RGB tone must reach the renderer", it.tonal)
                    assertTrue("white source motif stays opaque", Color.alpha(it.mask!!.getPixel(32, 32)) > 220)
                }.mask!!
            }
            masks.forEach { assertTrue("border rendering cannot erode source artwork", masks.last().sameAs(it)) }
            val cached = AospMonochromeMaskStrategy(generatedBorderRatio = 0f).generate(drawable, identity)!!
            assertTrue(cached.cacheHit)
            assertTrue("cache hits retain the tone/coverage contract", cached.tonal)
        } finally {
            IconDrawableCache.remove(identity)
            MonochromeCache.shared.clear()
            original.recycle()
        }
    }

    @Test
    fun oddWidthCompatibilityBitmapKeepsEveryRowAndWhiteCenter() {
        val original = Bitmap.createBitmap(31, 29, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(7f, 7f, 24f, 22f, Paint().apply { color = Color.BLACK })
            Canvas(this).drawRect(12f, 11f, 19f, 18f, Paint().apply { color = Color.WHITE })
            setPixel(0, 0, Color.TRANSPARENT)
            for (y in 1 until height) setPixel(1, y, Color.argb(y * 8, 128, 128, 128))
        }
        val mask = LabMonochromeExtractor.extractFromBitmap(original, -0.5, borderRatio = 0f)
        assertNotNull(mask)
        try {
            assertEquals(31, mask!!.width)
            assertEquals(29, mask.height)
            assertEquals(Bitmap.Config.ARGB_8888, mask.config)
            assertEquals(255, Color.alpha(mask.getPixel(15, 14)))
            assertEquals("opaque white background is actual source coverage", 255, Color.alpha(mask.getPixel(0, 28)))
            assertEquals("only genuine source transparency is a hole", 0, Color.alpha(mask.getPixel(0, 0)))
            assertEquals(Color.BLACK, mask.getPixel(8, 14))
            assertEquals(Color.WHITE, mask.getPixel(15, 14))
            for (y in 0 until original.height) for (x in 0 until original.width) {
                assertEquals("coverage at odd-width row $y column $x",
                    Color.alpha(original.getPixel(x, y)), Color.alpha(mask.getPixel(x, y)))
            }
        } finally {
            mask?.recycle()
            original.recycle()
        }
    }

    @Test
    fun foregroundRenderingRestoresDrawableBounds() {
        val foreground = ColorDrawable(Color.WHITE).apply { bounds = Rect(3, 4, 19, 23) }
        val background = ColorDrawable(Color.BLUE).apply { bounds = Rect(2, 7, 25, 29) }
        val adaptive = AdaptiveIconDrawable(background, foreground)
        val oldForeground = Rect(foreground.bounds)
        val oldBackground = Rect(background.bounds)
        AospMonochromeFactory.wrap(adaptive, 63, borderRatio = 0.5f)?.recycle()
        assertEquals(oldForeground, foreground.bounds)
        assertEquals(oldBackground, background.bounds)
    }

}
