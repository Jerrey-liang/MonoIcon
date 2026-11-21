package com.jerrey.monoicon

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.hook.LauncherLargeIconSource
import com.jerrey.monoicon.theme.render.LauncherIconRenderer
import com.jerrey.monoicon.theme.render.LauncherIconRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherLargeIconSourceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearCaches() {
        LauncherIconRenderer.clear()
        IconDrawableCache.clear()
    }

    private fun request(): LauncherIconRequest {
        val component = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)?.component)
        return LauncherIconRequest(
            component.flattenToString(),
            Process.myUserHandle(),
            context.resources.displayMetrics.densityDpi,
            context.resources.configuration.hashCode(),
        )
    }

    @Test
    fun coldLargeIconCapturesNativeApkLayersAndRepublishesAfterLruEviction() {
        val request = request()
        var captures = 0
        val first = LauncherLargeIconSource.capture(context, request) { info, _ ->
            assertEquals(request.identity, info.componentName.flattenToString())
            assertEquals(request.user, info.user)
            captures++
        }
        assertTrue(first is AdaptiveIconDrawable)
        assertNotNull((first as AdaptiveIconDrawable).monochrome)
        assertSame(first, IconDrawableCache.get(request.identity))

        IconDrawableCache.clear()
        val second = LauncherLargeIconSource.capture(context, request) { _, _ -> captures++ }
        assertSame(first, second)
        assertSame(first, IconDrawableCache.get(request.identity))
        assertEquals(1, captures)
    }

    @Test
    fun captureReplacesThemedCacheWithTheExactApkResourceAndNativeLayers() {
        val request = request()
        val component = requireNotNull(ComponentName.unflattenFromString(request.identity))
        val launcherApps = requireNotNull(context.getSystemService(LauncherApps::class.java))
        val activity = requireNotNull(launcherApps.resolveActivity(Intent().setComponent(component), request.user))
        val info = activity.activityInfo
        val resources = context.packageManager.getResourcesForApplication(info.applicationInfo)
        val expected = requireNotNull(
            resources.getDrawableForDensity(info.iconResource, request.densityDpi, null),
        ).mutate()

        // A component-keyed cache can contain a previously OEM-themed icon.
        // Capturing must replace it with the APK resource for this request.
        IconDrawableCache.put(request.identity, ColorDrawable(Color.MAGENTA))
        val captured = requireNotNull(LauncherLargeIconSource.capture(context, request))
        assertSame(captured, IconDrawableCache.get(request.identity))
        assertPixelsEqual("captured full icon is the APK resource", expected, captured)
        assertTrue(expected is AdaptiveIconDrawable)
        assertTrue(captured is AdaptiveIconDrawable)
        val expectedAdaptive = expected as AdaptiveIconDrawable
        val capturedAdaptive = captured as AdaptiveIconDrawable
        assertPixelsEqual("APK background is retained", expectedAdaptive.background, capturedAdaptive.background)
        assertPixelsEqual("APK foreground is retained", expectedAdaptive.foreground, capturedAdaptive.foreground)
        assertNotNull(expectedAdaptive.monochrome)
        assertPixelsEqual("authored monochrome layer is retained", expectedAdaptive.monochrome, capturedAdaptive.monochrome)
    }

    @Test
    fun missingProfileDoesNotLoadOwnerIcon() {
        val owner = request()
        val ownerSource = LauncherLargeIconSource.capture(context, owner)
        assertNotNull(ownerSource)
        val missingProfile = owner.copy(user = UserHandle.getUserHandleForUid(Int.MAX_VALUE))
        assertNull(LauncherLargeIconSource.capture(context, missingProfile))
        assertSame(ownerSource, IconDrawableCache.get(owner.identity))
    }

    @Test
    fun missingComponentDoesNotReuseThePackageOrThemedCacheIcon() {
        val pkg = context.packageName
        val missing = request().copy(identity = "$pkg/$pkg.MissingLauncherActivity")
        val cachedTheme = ColorDrawable(Color.MAGENTA)
        IconDrawableCache.put(missing.identity, cachedTheme)

        assertNull(LauncherLargeIconSource.capture(context, missing))
        assertSame(cachedTheme, IconDrawableCache.get(missing.identity))
    }

    @Test
    fun densityAndConfigurationEachHaveSeparateRawCaptures() {
        val request = request()
        var captures = 0
        val capture = { next: LauncherIconRequest ->
            assertNotNull(LauncherLargeIconSource.capture(context, next) { _, _ -> captures++ })
        }
        capture(request)
        capture(request.copy(densityDpi = request.densityDpi + 80))
        capture(request.copy(configHash = request.configHash + 1))
        assertEquals(3, captures)
    }

    private fun assertPixelsEqual(message: String, expected: Drawable?, actual: Drawable?) {
        assertNotNull(message, expected)
        assertNotNull(message, actual)
        val expectedBitmap = render(requireNotNull(expected))
        val actualBitmap = render(requireNotNull(actual))
        try {
            assertTrue(message, expectedBitmap.sameAs(actualBitmap))
        } finally {
            expectedBitmap.recycle()
            actualBitmap.recycle()
        }
    }

    private fun render(drawable: Drawable): Bitmap {
        val previousBounds = Rect(drawable.bounds)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            return bitmap
        } finally {
            drawable.bounds = previousBounds
        }
    }
}
