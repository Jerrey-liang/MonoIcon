package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.SystemUiIconHooks
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.IconShape
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SystemUI notification app-icon tests (Phase 11).
 *
 * The real `AppIconsManager` lives in the SystemUI APK and is absent from the module's
 * test process, so the extracted, testable pieces are covered here: the RemoteViews
 * rasterization helper, the package-identity + raw-drawable cache path, the Lawnicons
 * package lookup and the "don't restyle our own drawable" guard predicate.
 */
@RunWith(AndroidJUnit4::class)
class SystemUiIconInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearCaches() {
        IconDrawableCache.clear()
        MonochromeCache.shared.clear()
        PixelMonetColorEngine.invalidate()
    }

    /**
     * RemoteViews slot geometry: the themed bitmap must keep the original size and
     * density, and must actually contain rendered icon content. (Shape behaviour is
     * covered by `CircleIconShapeInstrumentedTest`; the plate colour here depends on
     * the dynamic-colour engine's state in the test process.)
     */
    @Test
    fun rasterizeKeepsRequestedSizeAndDensity() {
        val themed = ThemedIconBuilder.build(fakeAdaptiveIcon(), "com.example.pkg") ?: run {
            org.junit.Assume.assumeTrue("themed drawable unavailable in this build", false)
            return
        }

        val bitmap = SystemUiIconHooks.rasterize(themed.drawable, 192, 96, 320)

        assertEquals(192, bitmap.width)
        assertEquals(96, bitmap.height)
        assertEquals(320, bitmap.density)

        // Content is present (the centre column of a themed icon is never empty).
        var opaque = 0
        for (y in 0 until bitmap.height) {
            if (Color.alpha(bitmap.getPixel(bitmap.width / 2, y)) > 0) opaque++
        }
        assertTrue("centre column rendered (opaque=$opaque)", opaque > 0)
    }

    @Test
    fun packageIdentityHitsLawniconsPackageEntry() {
        LawniconsAssetSource.init(context)
        val byPackage = LawniconsAssetSource.lookupMask("com.tencent.mobileqq", 192)
        org.junit.Assume.assumeTrue("Lawnicons bundle not available", byPackage != null)

        assertNotNull(byPackage)
        assertTrue(Color.alpha(byPackage!!.getPixel(96, 96)) > 0)
        assertTrue(byPackage.width == 192 && byPackage.height == 192)
    }

    @Test
    fun iconDrawableCacheStoresPackageKey() {
        val raw = ColorDrawable(Color.MAGENTA)
        IconDrawableCache.put("com.example.notif", raw)

        assertSame(raw, IconDrawableCache.get("com.example.notif"))
        assertSame(raw, IconDrawableCache.getByPackage("com.example.notif"))

        IconDrawableCache.removeByPrefix("com.example.notif")
        assertEquals(null, IconDrawableCache.get("com.example.notif"))
    }

    @Test
    fun styledIconGuardOnlyPassesOurOwnDrawable() {
        val themed = ColoredMonochromeDrawable(ColorDrawable(Color.BLACK).let {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        }, Color.WHITE, Color.BLACK)

        assertTrue(SystemUiIconHooks.isOwnThemedDrawable(themed))
        assertFalse(SystemUiIconHooks.isOwnThemedDrawable(ColorDrawable(Color.RED)))
        assertFalse(SystemUiIconHooks.isOwnThemedDrawable(null))
        assertFalse(SystemUiIconHooks.isOwnThemedDrawable("not a drawable"))
    }

    /** Adaptive icon with a white plate and a black glyph in the middle. */
    private fun fakeAdaptiveIcon(): AdaptiveIconDrawable {
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(fg).drawRect(20f, 20f, 44f, 44f, Paint().apply { color = Color.BLACK })
        return AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(context.resources, fg),
        )
    }
}
