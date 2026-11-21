package com.jerrey.monoicon

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.AppIconOverridesCodec
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.theme.render.LauncherPaletteSnapshot
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigManagerRefreshInstrumentedTest {
    private val values = ConcurrentHashMap<String, Any>()
    private val providerCalls = AtomicInteger()
    private val saved = mutableMapOf<String, Any?>()
    private lateinit var prefs: SharedPreferences
    private val timestampFields = listOf(
        "lastRefreshMs", "lastLawniconsRefreshMs", "lastCircleRefreshMs",
        "lastLawniconsPackagesRefreshMs", "lastColorEngineRefreshMs",
        "lastGeneratedIconBorderRefreshMs",
        "lastGeneratedArtworkAppearanceRefreshMs",
        "lastIconOverridesRefreshMs",
    )
    private val cachedDefaults = mapOf(
        "cachedEnabled" to true,
        "hookSafetyBlocked" to false,
        "cachedLawniconsEnabled" to true,
        "cachedCircleIconsEnabled" to false,
        "cachedGeneratedArtworkFollowIconRoundness" to false,
    )

    @Before
    fun installRemotePreferences() {
        val provider = field("remotePrefsProvider")
        saved[provider.name] = provider.get(null)
        prefs = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getBoolean", "getString", "getStringSet", "getInt" -> values[arguments!![0] as String] ?: arguments[1]
                "contains" -> values.containsKey(arguments!![0] as String)
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        val testProvider: () -> SharedPreferences? = {
            providerCalls.incrementAndGet()
            prefs
        }
        provider.set(null, testProvider)
        timestampFields.forEach { name ->
            val timestamp = field(name).get(null) as AtomicLong
            saved[name] = timestamp.get()
            expire(name)
        }
        cachedDefaults.forEach { (name, default) ->
            val cached = field(name).get(null) as AtomicBoolean
            saved[name] = cached.get()
            cached.set(default)
        }
        listOf("cachedIconRoundness", "cachedIconSize", "cachedGeneratedIconBorder",
            "cachedGeneratedArtworkRoundness").forEach { name ->
            val cached = field(name).get(null) as AtomicInteger
            saved[name] = cached.get()
            cached.set(if (name == "cachedGeneratedIconBorder") 100 else 0)
        }
        mapOf("lastLawniconsValue" to true, "lastCircleValue" to false,
            "cachedLawniconsDisabledPackages" to emptySet<String>(), "cachedColorEngineId" to "material_2025",
            "cachedDefaultIconSourceStyle" to null, "cachedAppIconOverrides" to emptyMap<String, AppIconOverrides>())
            .forEach { (name, default) ->
                val previous = field(name)
                saved[name] = previous.get(null)
                previous.set(null, default)
            }
    }

    @After
    fun restoreConfiguration() {
        saved.forEach { (name, value) ->
            val target = field(name)
            val current = target.get(null)
            if (current is AtomicLong) current.set(value as Long)
            else if (current is AtomicBoolean) current.set(value as Boolean)
            else if (current is AtomicInteger) current.set(value as Int)
            else target.set(null, value)
        }
    }

    @Test
    fun hookOverridesRefreshBeforeRecipeLookupAndSurviveBinderFailure() {
        values["default_icon_source_style"] = "native"
        values["icon_roundness_percent"] = 20
        values["app_icon_overrides"] = AppIconOverridesCodec.encode(mapOf(
            "com.example.a" to AppIconOverrides(IconSourceStyle.GENERATED, sizePercent = 0, borderPercent = 12),
        ))
        val effective = ConfigManager.getEffectiveIconStyle("com.example.a/Main")
        assertEquals(IconSourceStyle.GENERATED, effective.style)
        assertEquals(20, effective.roundnessPercent)
        assertEquals(0, effective.sizePercent)
        assertEquals(12, effective.borderPercent)
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle("com.example.other").style)
        MonochromeCache.shared.put("override-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        values["app_icon_overrides"] = ""
        expire("lastIconOverridesRefreshMs")
        ConfigManager.refreshIconPreferences()
        assertEquals(null, MonochromeCache.shared.get("override-refresh-test"))
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle("com.example.a").style)
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastIconOverridesRefreshMs")
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle("com.example.a").style)
    }

    @Test
    fun remoteSourceDefaultsAndLegacyChoicesMatchUiSemanticsWithoutWritingPreferences() {
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle(null).style)
        assertTrue(ConfigManager.permitsLawniconsFallback(null))
        assertFalse(values.containsKey("default_icon_source_style"))
        values["lawnicons_enabled"] = true
        expire("lastIconOverridesRefreshMs")
        assertEquals(IconSourceStyle.LAWNICONS, ConfigManager.getEffectiveIconStyle(null).style)
        values["lawnicons_enabled"] = false
        expire("lastIconOverridesRefreshMs")
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(null).style)
        assertFalse(ConfigManager.permitsLawniconsFallback(null))
        for (style in IconSourceStyle.entries) {
            values["default_icon_source_style"] = style.id
            values["lawnicons_enabled"] = true
            expire("lastIconOverridesRefreshMs")
            assertEquals(style, ConfigManager.getEffectiveIconStyle(null).style)
            values["lawnicons_enabled"] = false
            expire("lastIconOverridesRefreshMs")
            assertEquals(style, ConfigManager.getEffectiveIconStyle(null).style)
        }
    }

    @Test
    fun runtimeBundleLookupFollowsSourceChoiceAndKeepsLegacyDirectNativeExclusions() {
        val pkg = "com.example.source"
        for (style in IconSourceStyle.entries) {
            values["default_icon_source_style"] = style.id
            values["app_icon_overrides"] = ""
            expire("lastIconOverridesRefreshMs")
            assertEquals(style.permitsLawniconsFallback, ConfigManager.permitsLawniconsFallback(pkg))
            for (override in IconSourceStyle.entries) {
                values["app_icon_overrides"] = AppIconOverridesCodec.encode(mapOf(pkg to AppIconOverrides(style = override)))
                expire("lastIconOverridesRefreshMs")
                assertEquals(override, ConfigManager.getEffectiveIconStyle(pkg).style)
                assertEquals(override.permitsLawniconsFallback, ConfigManager.permitsLawniconsFallback(pkg))
            }
        }
        values["default_icon_source_style"] = IconSourceStyle.LAWNICONS.id
        values["app_icon_overrides"] = ""
        values["lawnicons_disabled_packages"] = setOf(pkg)
        expire("lastIconOverridesRefreshMs")
        expire("lastLawniconsPackagesRefreshMs")
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        values["app_icon_overrides"] = AppIconOverridesCodec.encode(mapOf(pkg to AppIconOverrides(sizePercent = 60)))
        expire("lastIconOverridesRefreshMs")
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertEquals(60, ConfigManager.getEffectiveIconStyle(pkg).sizePercent)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        values["app_icon_overrides"] = AppIconOverridesCodec.encode(mapOf(pkg to AppIconOverrides(style = IconSourceStyle.NATIVE)))
        expire("lastIconOverridesRefreshMs")
        assertEquals("An old saved native style does not erase its legacy exclusion",
            IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        values["lawnicons_disabled_packages"] = emptySet<String>()
        expire("lastLawniconsPackagesRefreshMs")
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertTrue("an explicit source selection clears the legacy exclusion", ConfigManager.permitsLawniconsFallback(pkg))
    }

    @Test
    fun concurrentHooksRefreshEachFlagOnce() {
        val readers = listOf(
            ConfigManager::isEnabled to true,
            ConfigManager::isLawniconsEnabled to true,
            ConfigManager::isCircleIconsEnabled to false,
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            readers.forEachIndexed { index, (read, expected) ->
                expire(timestampFields[index])
                providerCalls.set(0)
                val start = CountDownLatch(1)
                val results = List(32) {
                    executor.submit<Boolean> {
                        start.await()
                        read()
                    }
                }
                start.countDown()
                results.forEach { assertEquals(expected, it.get(5, TimeUnit.SECONDS)) }
                assertEquals("one preference read per refresh window", 1, providerCalls.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun safetyLatchOverridesEnabledPreferencesAndCacheRefresh() {
        values["enabled"] = true
        values["safety_blocked"] = true
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
        values["safety_blocked"] = false
        expire("lastRefreshMs")
        assertTrue(ConfigManager.isEnabled())
        ConfigManager.disableForHooks()
        expire("lastRefreshMs")
        assertFalse("a blocked process requires a new process, not a preference refresh", ConfigManager.isEnabled())
    }

    @Test
    fun remoteFailureRetainsPreviouslyObservedSafetyDisable() {
        values["safety_blocked"] = true
        assertFalse(ConfigManager.isEnabled())
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
        val missing: () -> SharedPreferences? = { null }
        field("remotePrefsProvider").set(null, missing)
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())
    }

    @Test
    fun notificationIconsIgnoreLegacyOptOutWithoutReadingRemotePreferences() {
        values["notification_icons"] = false
        providerCalls.set(0)
        repeat(32) { assertTrue(ConfigManager.isNotificationIconsEnabled()) }
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun enabledRetainsItsOneSecondCacheAndThemeVariantRemainLive() {
        assertTrue(ConfigManager.isEnabled())
        values["enabled"] = false
        assertTrue("the current one-second window is retained", ConfigManager.isEnabled())
        expire("lastRefreshMs")
        assertFalse(ConfigManager.isEnabled())

        assertEquals("pixel_default", ConfigManager.getThemeId())
        assertEquals("tonal_spot", ConfigManager.getVariantId())
        values["theme_id"] = "test_theme"
        values["variant_id"] = "expressive"
        assertEquals("test_theme", ConfigManager.getThemeId())
        assertEquals("expressive", ConfigManager.getVariantId())
    }

    @Test
    fun perAppAndEngineRefreshClearCachedMasksBeforeRecipeLookup() {
        values["lawnicons_disabled_packages"] = setOf("app.disabled")
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        assertTrue(ConfigManager.isLawniconsEnabledForPackage("app.new"))
        values["lawnicons_disabled_packages"] = emptySet<String>()
        assertFalse("cached choice remains during the refresh window", ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        MonochromeCache.shared.put("per-app-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        expire("lastLawniconsPackagesRefreshMs")
        ConfigManager.refreshIconPreferences()
        assertTrue(ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        assertEquals(0, MonochromeCache.shared.size)

        values["color_engine_id"] = "material_3"
        MonochromeCache.shared.put("engine-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        expire("lastColorEngineRefreshMs")
        ConfigManager.refreshIconPreferences()
        assertEquals("material_3", ConfigManager.getColorEngineId())
        assertEquals(0, MonochromeCache.shared.size)

        values["color_engine_id"] = "material_2025"
        assertEquals("material_3", ConfigManager.getColorEngineId())
        ConfigManager.invalidateHookIconPreferences()
        assertEquals("material_2025", ConfigManager.getColorEngineId())
    }

    @Test
    fun unavailableRemotePreferencesRetainAppChoicesAndEngine() {
        values["lawnicons_disabled_packages"] = setOf("app.disabled")
        values["color_engine_id"] = "material_3"
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        assertEquals("material_3", ConfigManager.getColorEngineId())
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastLawniconsPackagesRefreshMs")
        expire("lastColorEngineRefreshMs")
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        assertEquals("material_3", ConfigManager.getColorEngineId())
    }

    @Test
    fun geometryMigratesLegacyCircleAndInvalidatesCachedIconsAtIntermediateValues() {
        values["circle_icons"] = true
        assertEquals(100, ConfigManager.getIconRoundnessPercent())
        assertEquals(0, ConfigManager.getIconSizePercent())
        values["icon_roundness_percent"] = 46
        values["icon_size_percent"] = 73
        MonochromeCache.shared.put("geometry-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        ConfigManager.invalidateHookIconPreferences()
        assertEquals(46, ConfigManager.getIconRoundnessPercent())
        assertEquals(73, ConfigManager.getIconSizePercent())
        assertEquals(0, MonochromeCache.shared.size)
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastCircleRefreshMs")
        assertEquals(46, ConfigManager.getIconRoundnessPercent())
        assertEquals(73, ConfigManager.getIconSizePercent())
    }

    @Test
    fun borderRefreshInvalidatesMasksAndRetainsTheLastValueWhenBinderFails() {
        assertEquals(1f, ConfigManager.generatedIconBorderRatio, 0f)
        values["generated_icon_border_percent"] = 37
        assertEquals("the current refresh window remains cached", 1f, ConfigManager.generatedIconBorderRatio, 0f)
        MonochromeCache.shared.put("border-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        ConfigManager.invalidateHookIconPreferences()
        assertEquals(0.37f, ConfigManager.generatedIconBorderRatio, 0.001f)
        assertEquals(0, MonochromeCache.shared.size)
        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastGeneratedIconBorderRefreshMs")
        assertEquals(0.37f, ConfigManager.generatedIconBorderRatio, 0.001f)
    }

    @Test
    fun generatedArtworkAppearanceRefreshesTogetherAndRetainsLastValuesOnFailure() {
        assertEquals(0, ConfigManager.getGeneratedArtworkRoundnessPercent())
        assertFalse(ConfigManager.getGeneratedArtworkFollowIconRoundness())
        values["generated_artwork_roundness_percent"] = 65
        values["generated_artwork_follow_icon_roundness"] = true
        assertEquals("the current refresh window remains cached", 0,
            ConfigManager.getGeneratedArtworkRoundnessPercent())
        MonochromeCache.shared.put("artwork-refresh-test", Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8))
        ConfigManager.invalidateHookIconPreferences()
        assertEquals(65, ConfigManager.getGeneratedArtworkRoundnessPercent())
        assertTrue(ConfigManager.getGeneratedArtworkFollowIconRoundness())
        assertEquals(0, MonochromeCache.shared.size)

        values["icon_roundness_percent"] = 20
        values["app_icon_overrides"] = AppIconOverridesCodec.encode(mapOf(
            "com.example.artwork" to AppIconOverrides(roundnessPercent = 80,
                generatedArtworkRoundnessPercent = 10,
                generatedArtworkFollowIconRoundness = false),
        ))
        expire("lastCircleRefreshMs")
        expire("lastIconOverridesRefreshMs")
        val effective = ConfigManager.getEffectiveIconStyle("com.example.artwork/Main")
        assertEquals(10, effective.generatedArtworkRoundnessPercent)
        assertFalse(effective.generatedArtworkFollowIconRoundness)
        assertEquals(0.05f, effective.generatedArtworkRadiusFraction, 0.0001f)

        val unavailable: () -> SharedPreferences? = { error("Binder unavailable") }
        field("remotePrefsProvider").set(null, unavailable)
        expire("lastGeneratedArtworkAppearanceRefreshMs")
        assertEquals(65, ConfigManager.getGeneratedArtworkRoundnessPercent())
        assertTrue(ConfigManager.getGeneratedArtworkFollowIconRoundness())
    }

    @Test
    fun launcherPalettePreferenceListenerImmediatelyRefreshesArtworkAppearanceAndInvalidatesDrawables() {
        assertEquals(0, ConfigManager.getGeneratedArtworkRoundnessPercent())
        assertFalse(ConfigManager.getGeneratedArtworkFollowIconRoundness())

        @Suppress("UNCHECKED_CAST")
        val drawables = paletteField("drawables").get(null) as MutableMap<Drawable, Unit>
        val invalidationPosted = paletteField("invalidationPosted").get(null) as AtomicBoolean
        val invalidations = Semaphore(0)
        val drawable = ColorDrawable(Color.TRANSPARENT).apply {
            callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) { invalidations.release() }
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
                override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
            }
        }
        val previousDrawables = synchronized(drawables) { drawables.toMap().also { drawables.clear() } }
        val previousInvalidationPosted = invalidationPosted.getAndSet(false)
        val listener = paletteField("prefsListener").get(null) as
            SharedPreferences.OnSharedPreferenceChangeListener
        try {
            synchronized(drawables) { drawables[drawable] = Unit }
            listOf(
                Triple("generated_artwork_roundness_percent", 65, true),
                Triple("generated_artwork_follow_icon_roundness", 25, false),
            ).forEach { (key, roundness, follow) ->
                values["generated_artwork_roundness_percent"] = roundness
                values["generated_artwork_follow_icon_roundness"] = follow
                listener.onSharedPreferenceChanged(prefs, key)
                assertEquals(roundness, ConfigManager.getGeneratedArtworkRoundnessPercent())
                assertEquals(follow, ConfigManager.getGeneratedArtworkFollowIconRoundness())
                assertTrue("$key must invalidate observed drawables",
                    invalidations.tryAcquire(5, TimeUnit.SECONDS))
            }
        } finally {
            synchronized(drawables) {
                drawables.clear()
                drawables.putAll(previousDrawables)
            }
            invalidationPosted.set(previousInvalidationPosted)
            drawable.callback = null
        }
    }

    private fun expire(name: String) {
        (field(name).get(null) as AtomicLong).set(SystemClock.elapsedRealtime() - 1_000L)
    }

    private fun field(name: String) = ConfigManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun paletteField(name: String) = LauncherPaletteSnapshot::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
