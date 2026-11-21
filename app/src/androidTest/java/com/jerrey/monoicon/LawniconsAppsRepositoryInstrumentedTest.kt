package com.jerrey.monoicon

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.ui.screens.LawniconsApp
import com.jerrey.monoicon.ui.screens.LawniconsAppsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LawniconsAppsRepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun listsOnlyDesktopLauncherPackagesAndReportsActualResourceCoverage() = runBlocking {
        LawniconsAssetSource.init(context)
        val pm = context.packageManager
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).filter { it.activityInfo?.let { info -> info.enabled && info.applicationInfo.enabled && info.exported } == true }
        val launcherPackages = launchers.mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val apps = LawniconsAppsRepository.loadLauncherApps(context)
        assertEquals(launcherPackages, apps.mapTo(mutableSetOf()) { it.packageName })
        assertEquals(apps, LawniconsAppsRepository.loadInstalledApps(context))
        assertEquals(apps.size, apps.distinctBy { it.packageName }.size)
        assertTrue(apps.all { it.label.isNotBlank() })
        apps.forEach { app ->
            val candidates = launchers.filter { it.activityInfo.packageName == app.packageName }
            val preferred = pm.getLaunchIntentForPackage(app.packageName)?.component
            val expected = candidates.firstOrNull {
                preferred == ComponentName(it.activityInfo.packageName, it.activityInfo.name)
            } ?: candidates.first()
            assertEquals(ComponentName(expected.activityInfo.packageName, expected.activityInfo.name).flattenToString(),
                app.identity)
            assertTrue("Every listed identity must be a desktop launcher component", launchers.any {
                ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString() == app.identity
            })
            assertEquals(LawniconsAssetSource.hasAvailableMask(app.identity), app.hasLawnicons)
            assertEquals(LawniconsAppsRepository.hasUsableNativeMonochrome(originalDrawable(app)),
                app.hasNativeMonochrome)
        }
        assertTrue("The module's adaptive icon contains a native monochrome layer",
            apps.first { it.packageName == context.packageName }.hasNativeMonochrome)
    }

    @Test
    fun detailResourceRefreshUsesTheApkAndRuntimeLawniconsMapping() = runBlocking {
        val app = LawniconsAppsRepository.loadLauncherApps(context).first { it.packageName == context.packageName }
        val stale = app.copy(hasNativeMonochrome = !app.hasNativeMonochrome, hasLawnicons = !app.hasLawnicons)
        assertEquals(app, LawniconsAppsRepository.loadAppResources(context, stale))
    }

    @Test
    fun detailResourceRefreshDoesNotReportCapabilitiesWithoutALauncherEntry() = runBlocking {
        val missing = LawniconsApp("com.example.monoicon.missing", "Missing",
            "com.example.monoicon.missing/.Main", hasNativeMonochrome = true, hasLawnicons = true)
        val refreshed = LawniconsAppsRepository.loadAppResources(context, missing)
        assertEquals(false, refreshed.hasNativeMonochrome)
        assertEquals(false, refreshed.hasLawnicons)
    }

    @Test
    fun nativeCapabilityRejectsPlaceholderMonochromeLayers() {
        for (placeholder in listOf(Color.TRANSPARENT, Color.WHITE)) {
            val adaptive = AdaptiveIconDrawable(
                ColorDrawable(Color.WHITE),
                ColorDrawable(Color.BLACK),
                ColorDrawable(placeholder),
            )
            assertFalse(LawniconsAppsRepository.hasUsableNativeMonochrome(adaptive))
        }

        val glyph = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            Canvas(this).drawRect(16f, 16f, 48f, 48f, android.graphics.Paint().apply { color = Color.WHITE })
        }
        try {
            val adaptive = AdaptiveIconDrawable(
                ColorDrawable(Color.WHITE),
                ColorDrawable(Color.BLACK),
                BitmapDrawable(context.resources, glyph),
            )
            assertTrue(LawniconsAppsRepository.hasUsableNativeMonochrome(adaptive))
        } finally {
            glyph.recycle()
        }
    }

    @Test
    fun legacyOriginalIconsUseTheFrameworkAdaptiveMask() {
        val size = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        val drawable = LawniconsAppsRepository.prepareOriginalIcon(ColorDrawable(Color.RED), size)
        assertTrue(drawable is AdaptiveIconDrawable)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue(Color.alpha(bitmap.getPixel(size / 2, size / 2)) > 0)
    }

    @Test
    fun originalIconUsesSystemSizeAndUnmodifiedApkLayers() = runBlocking {
        val app = LawniconsAppsRepository.loadLauncherApps(context).first { it.packageName == context.packageName }
        val original = LawniconsAppsRepository.loadOriginalIcon(context, app)!!
        val systemSize = context.resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        assertEquals(systemSize, original.width)
        assertEquals(systemSize, original.height)
        val expected = Bitmap.createBitmap(systemSize, systemSize, Bitmap.Config.ARGB_8888)
        originalDrawable(app).apply { setBounds(0, 0, systemSize, systemSize) }.draw(Canvas(expected))
        assertTrue("The list must retain the APK's colors and framework adaptive mask", original.sameAs(expected))
        assertSame(original, LawniconsAppsRepository.loadOriginalIcon(context, app))
        LawniconsAppsRepository.invalidatePreviews()
        assertNotSame(original, LawniconsAppsRepository.loadOriginalIcon(context, app))
    }

    @Test
    fun previewsUseDraftParametersWithoutMutatingPersistedPreferences() = runBlocking {
        val pkg = context.packageName
        val app = LawniconsApp(pkg, "MonoIcon", pkg)
        val before = ConfigManager.getAllAppIconOverridesFromUi()
        val globalBefore = ConfigManager.getEffectiveIconStyleFromUi(null)
        val draft = AppIconOverrides(IconSourceStyle.GENERATED, 70, 65, 25)
        val bitmap = LawniconsAppsRepository.renderPreview(context, app, draft)
        assertNotNull(bitmap)
        assertEquals(192, bitmap!!.width)
        assertEquals(192, bitmap.height)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("The preview must contain artwork", pixels.toSet().size > 1)
        assertSame(bitmap, LawniconsAppsRepository.renderPreview(context, app, draft))
        val differentSize = LawniconsAppsRepository.renderPreview(context, app, draft.copy(sizePercent = 15))
        assertNotSame(bitmap, differentSize)
        assertEquals(before, ConfigManager.getAllAppIconOverridesFromUi())
        assertEquals(globalBefore, ConfigManager.getEffectiveIconStyleFromUi(null))
    }

    @Test
    fun invalidationDoesNotReuseAnOldPreview() = runBlocking {
        val app = LawniconsApp(context.packageName, "MonoIcon", context.packageName)
        val draft = AppIconOverrides(style = IconSourceStyle.NATIVE)
        val first = LawniconsAppsRepository.renderPreview(context, app, draft)
        assertNotNull(first)
        LawniconsAppsRepository.invalidatePreviews()
        assertNotSame(first, LawniconsAppsRepository.renderPreview(context, app, draft))
    }

    private fun originalDrawable(app: LawniconsApp) = context.packageManager.let { pm ->
        val activity = pm.getActivityInfo(ComponentName.unflattenFromString(app.identity)!!,
            PackageManager.ComponentInfoFlags.of(0))
        pm.getResourcesForApplication(activity.applicationInfo).getDrawable(activity.iconResource, null).mutate()
    }
}
