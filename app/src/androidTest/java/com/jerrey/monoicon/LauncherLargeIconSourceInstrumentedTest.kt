package com.jerrey.monoicon

import android.graphics.drawable.AdaptiveIconDrawable
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
    fun missingProfileDoesNotLoadOwnerIcon() {
        val owner = request()
        val ownerSource = LauncherLargeIconSource.capture(context, owner)
        assertNotNull(ownerSource)
        val missingProfile = owner.copy(user = UserHandle.getUserHandleForUid(Int.MAX_VALUE))
        assertNull(LauncherLargeIconSource.capture(context, missingProfile))
        assertSame(ownerSource, IconDrawableCache.get(owner.identity))
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
}
