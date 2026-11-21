package com.jerrey.monoicon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
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
import com.jerrey.monoicon.theme.render.LauncherPaletteSnapshot
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
    private var restoreNotificationState: (() -> Unit)? = null

    @After
    fun clearCaches() {
        restoreNotificationState?.let { restore ->
            restoreNotificationState = null
            restore()
            return
        }
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

    @Test
    fun styledIconGuardHonorsBitmapDrawableReturnTypeAndPreservesBounds() {
        val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawCircle(16f, 16f, 8f, Paint().apply { color = Color.WHITE })
        val themed = ColoredMonochromeDrawable(mask, Color.WHITE, Color.BLACK, IconShape.CIRCLE)
        themed.setBounds(4, 6, 68, 70)
        val originalBounds = android.graphics.Rect(themed.bounds)
        assertSame(themed, SystemUiIconHooks.styledIconResult(themed, android.graphics.drawable.Drawable::class.java))
        val bitmap = SystemUiIconHooks.styledIconResult(themed, BitmapDrawable::class.java) as BitmapDrawable
        assertEquals(64, bitmap.bitmap.width)
        assertEquals(64, bitmap.bitmap.height)
        assertEquals(0, Color.alpha(bitmap.bitmap.getPixel(0, 0)))
        assertTrue(Color.alpha(bitmap.bitmap.getPixel(32, 32)) > 220)
        assertEquals(originalBounds, themed.bounds)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun notificationBitmapCacheRefreshesBorderWithTheSamePackageSizeAndColors() {
        val prefs = context.getSharedPreferences("notification_border_cache_test", Context.MODE_PRIVATE)
        val configFields = listOf("fallbackPrefs", "uiRemotePrefs", "remotePrefsProvider")
            .map { runtimeField(ConfigManager::class.java, it) }
        val engineFields = listOf("launcherContext", "cachedIconColors", "lastLoggedColors")
            .map { runtimeField(PixelMonetColorEngine::class.java, it) }
        val initialised = runtimeField(SystemUiIconHooks::class.java, "initialised")
        val palette = runtimeField(LauncherPaletteSnapshot::class.java, "palette")
        val savedFields = (configFields + engineFields + initialised + palette)
            .associateWith { it.get(null) }
        val listeners = runtimeField(ConfigManager::class.java, "configListeners").get(null)
            as MutableSet<(String?) -> Unit>
        val savedListeners = listeners.toSet()
        val bitmapCache = runtimeField(SystemUiIconHooks::class.java, "bitmapCache").get(null)
            as MutableMap<String, java.lang.ref.WeakReference<Bitmap>>
        val savedBitmaps = bitmapCache.toMap()
        val rawLock = runtimeField(IconDrawableCache::class.java, "lock").get(null)
        val rawStore = runtimeField(IconDrawableCache::class.java, "store").get(null)
            as Map<String, Drawable>
        val savedRaw = synchronized(rawLock) { rawStore.toMap() }
        val maskCache = runtimeField(MonochromeCache::class.java, "cache").get(MonochromeCache.shared)
            as LruCache<String, Bitmap>
        val savedMasks = maskCache.snapshot()
        val ownedBitmaps = mutableListOf<Bitmap>()

        // This test must not export temporary preferences or leave runtime state
        // behind, even when an assertion fails before the second render.
        restoreNotificationState = {
            savedFields.forEach { (field, value) -> field.set(null, value) }
            listeners.clear()
            listeners.addAll(savedListeners)
            bitmapCache.clear()
            bitmapCache.putAll(savedBitmaps)
            IconDrawableCache.clear()
            savedRaw.forEach { (identity, drawable) -> IconDrawableCache.put(identity, drawable) }
            maskCache.evictAll()
            savedMasks.forEach { (key, bitmap) -> maskCache.put(key, bitmap) }
            prefs.edit().clear().commit()
            ownedBitmaps.distinct().filterNot { it.isRecycled }.forEach(Bitmap::recycle)
        }
        listeners.clear()
        prefs.edit().clear()
            .putBoolean("lawnicons_enabled", false)
            .putInt("icon_roundness_percent", 0)
            .putInt("icon_size_percent", 100)
            .putInt("generated_icon_border_percent", 100)
            .putString("variant_id", "monochrome")
            .putString("color_engine_id", "material_2025")
            .commit()
        configFields[0].set(null, prefs)
        configFields[1].set(null, prefs)
        configFields[2].set(null, null)
        // Use cached raw input with manager=null, avoiding SystemUI startup and
        // wallpaper retrieval. Monochrome colors also stay independent of seed.
        initialised.setBoolean(null, true)
        engineFields[0].set(null, null)
        engineFields[1].set(null, null)
        palette.set(null, null)
        bitmapCache.clear()
        IconDrawableCache.clear()
        maskCache.evictAll()

        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also {
            it.density = Bitmap.DENSITY_NONE
            it.eraseColor(Color.rgb(0, 128, 223))
            Canvas(it).drawRect(22f, 22f, 42f, 42f, Paint().apply { color = Color.WHITE })
            ownedBitmaps += it
        }
        val pkg = "com.example.notificationborder"
        IconDrawableCache.put(pkg, BitmapDrawable(context.resources, source))
        val original = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            it.density = 320
            ownedBitmaps += it
        }
        val render = SystemUiIconHooks::class.java.getDeclaredMethod(
            "themedBitmap", Any::class.java, String::class.java, Bitmap::class.java,
        ).apply { isAccessible = true }
        fun renderNotification(): Bitmap = checkNotNull(
            render.invoke(SystemUiIconHooks, null, pkg, original) as? Bitmap,
        ).also { ownedBitmaps += it }

        val colors = PixelMonetColorEngine.getIconColors()
        val withBorder = renderNotification()
        assertSame("unchanged notification reuses the warm bitmap", withBorder, renderNotification())
        // A remote preference update does not clear this separate bitmap cache.
        // Keep it warm so the border lookup itself must notice the new value.
        assertTrue(prefs.edit().putInt("generated_icon_border_percent", 0).commit())
        assertEquals(0f, ConfigManager.generatedIconBorderRatio, 0f)
        val unchangedColors = PixelMonetColorEngine.getIconColors()
        assertEquals(colors.foreground, unchangedColors.foreground)
        assertEquals(colors.background, unchangedColors.background)
        val withoutBorder = renderNotification()
        assertNotSame("border changes cannot return the warm old bitmap", withBorder, withoutBorder)
        assertFalse("border changes must alter the rendered pixels", withBorder.sameAs(withoutBorder))
        assertEquals("white artwork keeps its foreground tint", colors.foreground, withoutBorder.getPixel(48, 48))
        assertEquals("white artwork is independent of border width", withBorder.getPixel(48, 48), withoutBorder.getPixel(48, 48))
        assertEquals(original.width, withoutBorder.width)
        assertEquals(original.height, withoutBorder.height)
        assertEquals(original.density, withoutBorder.density)
        assertSame("the new border result is cached too", withoutBorder, renderNotification())
    }

    private fun runtimeField(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply {
        isAccessible = true
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
