package com.jerrey.monoicon

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.hook.RecentsIconCompat
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recents task icon tests (Phase 10).
 *
 * The real `IconLoader` / `Task$TaskKey` live in the launcher APK and do not
 * exist in the module's test process, so the two extracted helpers
 * ([RecentsIconCompat], [ThemedIconBuilder]) are exercised directly with
 * stubs that expose the same accessor names as HyperOS.
 */
@RunWith(AndroidJUnit4::class)
class RecentsIconInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearCaches() {
        IconDrawableCache.clear()
        MonochromeCache.shared.clear()
        PixelMonetColorEngine.invalidate()
    }

    // ── Identity ──────────────────────────────────────────────────────

    @Test
    fun recentsIdentityBuildsPkgSlashClsFromTaskKey() {
        val component = ComponentName("com.example.pack", "com.example.pack.TopActivity")
        val key = StubTaskKey(top = component, component = component, pkg = "com.example.pack")

        assertEquals("com.example.pack/com.example.pack.TopActivity", RecentsIconCompat.identity(key))
    }

    @Test
    fun recentsIdentityFallsBackToComponentThenPackageThenNull() {
        // top == null → getComponent()
        val base = ComponentName("com.example.other", "com.example.other.BaseActivity")
        assertEquals(
            "com.example.other/com.example.other.BaseActivity",
            RecentsIconCompat.identity(StubTaskKey(top = null, component = base, pkg = "com.example.other")),
        )
        // no component at all → package name
        assertEquals(
            "com.example.pkgonly",
            RecentsIconCompat.identity(StubTaskKey(top = null, component = null, pkg = "com.example.pkgonly")),
        )
        // nothing usable → null
        assertNull(RecentsIconCompat.identity(StubTaskKey(top = null, component = null, pkg = null)))
        assertNull(RecentsIconCompat.identity(StubTaskKey(top = null, component = null, pkg = "")))
        // wrong type / null → null, never throws
        assertNull(RecentsIconCompat.identity(null))
        assertNull(RecentsIconCompat.identity("not a task key"))
    }

    // ── Raw drawable cache ────────────────────────────────────────────

    @Test
    fun iconDrawableCacheGetByPackageFindsPackageEntries() {
        val first = ColorDrawable(Color.RED)
        val second = ColorDrawable(Color.BLUE)
        IconDrawableCache.put("com.example.one/com.example.one.Main", first)
        IconDrawableCache.put("com.example.two/com.example.two.Main", second)

        assertSame(first, IconDrawableCache.getByPackage("com.example.one"))
        assertSame(second, IconDrawableCache.getByPackage("com.example.two"))
        assertNull(IconDrawableCache.getByPackage("com.example.three"))
        assertNull(IconDrawableCache.getByPackage(""))

        IconDrawableCache.removeByPrefix("com.example.one/")
        assertNull(IconDrawableCache.getByPackage("com.example.one"))
    }

    @Test
    fun recentsSourceDrawablePrefersExactThenPackageThenFallback() {
        val exact = ColorDrawable(Color.RED)
        val packageLevel = ColorDrawable(Color.GREEN)
        val fallback = ColorDrawable(Color.WHITE)

        // no cache → fallback
        assertSame(fallback, RecentsIconCompat.sourceDrawable("com.example.x/com.example.x.Main", fallback))

        // package-level only → package entry
        IconDrawableCache.put("com.example.x/com.example.x.Other", packageLevel)
        assertSame(packageLevel, RecentsIconCompat.sourceDrawable("com.example.x/com.example.x.Main", fallback))

        // exact entry wins
        IconDrawableCache.put("com.example.x/com.example.x.Main", exact)
        assertSame(exact, RecentsIconCompat.sourceDrawable("com.example.x/com.example.x.Main", fallback))

        // unknown identity (null) → fallback
        assertSame(fallback, RecentsIconCompat.sourceDrawable(null, fallback))
    }

    // ── Themed drawable builder (what the recents hook returns) ───────

    @Test
    fun themedIconBuilderProducesDrawableForAdaptiveIcon() {
        val identity = "test.recents/fake.Main"
        val result = ThemedIconBuilder.build(fakeAdaptiveIcon(), identity)

        assertNotNull("builder must produce a themed icon", result)
        val themed = result!!
        assertTrue("mask generated", themed.mask.width > 0 && themed.mask.height > 0)
        assertTrue("source tier recorded", themed.source > 0)

        // The recents icon view draws the returned drawable at a small size;
        // make sure it renders opaque content there.
        val size = 96
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        themed.drawable.setBounds(0, 0, size, size)
        themed.drawable.draw(Canvas(out))
        var opaque = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (Color.alpha(out.getPixel(x, y)) > 0) opaque++
            }
        }
        assertTrue("drawable renders content (opaque=$opaque)", opaque > 0)
    }

    @Test
    fun themedIconBuilderIsFailureSafe() {
        // A degenerate 1x1 transparent bitmap must not throw; it may return
        // null (caller then keeps MIUI's icon).
        val tiny = BitmapDrawable(context.resources, Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8))
        try {
            ThemedIconBuilder.build(tiny, "test.recents/tiny.Main")
        } catch (t: Throwable) {
            throw AssertionError("builder threw for a degenerate drawable: ${t.message}", t)
        }
    }

    /** Adaptive icon with a white plate and a black glyph in the middle. */
    private fun fakeAdaptiveIcon(): Drawable {
        val fg = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
        }
        Canvas(fg).drawRect(20f, 20f, 44f, 44f, Paint().apply { color = Color.BLACK })
        return AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(context.resources, fg),
        )
    }

    /** Mirrors the accessors of HyperOS `Task.TaskKey` used for identity. */
    private class StubTaskKey(
        private val top: ComponentName?,
        private val component: ComponentName?,
        private val pkg: String?,
    ) {
        fun getTopComponentOrBaseComponent(): ComponentName? = top
        fun getComponent(): ComponentName? = component
        fun getPackageName(): String? = pkg
    }
}
