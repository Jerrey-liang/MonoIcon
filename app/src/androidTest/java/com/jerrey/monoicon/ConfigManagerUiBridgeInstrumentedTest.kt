package com.jerrey.monoicon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.AppIconOverridesCodec
import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.render.IconShape
import com.jerrey.monoicon.theme.render.ThemedIconBuilder
import com.jerrey.monoicon.theme.mask.MaskStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The theme renderer uses the same getters as in-process icon rendering. */
@RunWith(AndroidJUnit4::class)
class ConfigManagerUiBridgeInstrumentedTest {
    private lateinit var local: SharedPreferences
    private lateinit var remote: SharedPreferences
    private val saved = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<(String?) -> Unit>()
    private var savedListeners = emptySet<(String?) -> Unit>()

    @Before
    fun isolateConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        local = context.getSharedPreferences("config_bridge_local_test", Context.MODE_PRIVATE)
        remote = context.getSharedPreferences("config_bridge_remote_test", Context.MODE_PRIVATE)
        local.edit().clear().commit()
        remote.edit().clear().commit()
        // Application registers theme synchronization in production. Preference
        // assertions must never publish the test values to the user's launcher.
        savedListeners = configListeners().toSet()
        configListeners().clear()
        for (name in listOf("fallbackPrefs", "uiRemotePrefs", "remotePrefsProvider")) {
            saved[name] = field(name).get(null)
            field(name).set(null, if (name == "fallbackPrefs") local else null)
        }
        saved["service"] = serviceState().value
        serviceState().value = null
    }

    @After
    fun restoreConfiguration() {
        listeners.forEach(ConfigManager::removeOnConfigChangedListener)
        saved.forEach { (name, value) ->
            if (name == "service") serviceState().value = value else field(name).set(null, value)
        }
        configListeners().addAll(savedListeners)
        local.edit().clear().commit()
        remote.edit().clear().commit()
    }

    @Test
    fun sharedRendererReadsUiSettingsBeforeHookInitialization() {
        local.edit()
            .putBoolean("enabled", false)
            .putBoolean("lawnicons_enabled", false)
            .putBoolean("circle_icons", true)
            .putBoolean("notification_icons", false)
            .putString("variant_id", "expressive")
            .putString("theme_id", "test_theme")
            .commit()

        assertFalse(ConfigManager.isEnabled())
        assertFalse(ConfigManager.isLawniconsEnabled())
        assertTrue("legacy notification opt-out no longer disables app icons", ConfigManager.isNotificationIconsEnabled())
        assertTrue(ConfigManager.isNotificationIconsEnabledFromUi())
        assertEquals(IconShape.CIRCLE, ConfigManager.iconShape())
        assertEquals("expressive", ConfigManager.getVariantId())
        assertEquals("test_theme", ConfigManager.getThemeId())

        // Hook processes always use their own remote source, even if UI state exists.
        remote.edit().putString("variant_id", "vibrant").commit()
        val hookProvider: () -> SharedPreferences? = { remote }
        field("remotePrefsProvider").set(null, hookProvider)
        assertEquals("vibrant", ConfigManager.getVariantId())
        assertEquals("expressive", ConfigManager.getVariantIdFromUi())
    }

    @Test
    fun ordinaryMasterSwitchCannotClearPersistentSafetyLatch() {
        field("uiRemotePrefs").set(null, remote)
        ConfigManager.setSafetyBlocked(true)
        assertTrue(local.getBoolean("safety_blocked", false))
        assertTrue(remote.getBoolean("safety_blocked", false))
        ConfigManager.setEnabled(true)
        assertFalse(ConfigManager.isEnabledFromUi())
        ConfigManager.setSafetyBlocked(false)
        assertTrue(ConfigManager.isEnabledFromUi())
    }

    @Test
    fun geometrySlidersMigrateLegacyAndReplayOfflineIntegerPreferences() {
        local.edit().putBoolean("circle_icons", true).commit()
        assertEquals(100, ConfigManager.getIconRoundnessPercentFromUi())
        assertEquals(0, ConfigManager.getIconSizePercentFromUi())
        ConfigManager.setIconRoundnessPercent(37)
        ConfigManager.setIconSizePercent(64)
        val reconcile = ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }
        remote.edit().putBoolean("circle_icons", false).commit()
        reconcile.invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)
        assertEquals(37, remote.getInt("icon_roundness_percent", -1))
        assertEquals(64, remote.getInt("icon_size_percent", -1))
        assertEquals(37, ConfigManager.getIconRoundnessPercent())
        ConfigManager.setIconRoundnessPercent(-10)
        ConfigManager.setIconSizePercent(200)
        assertEquals(0, ConfigManager.getIconRoundnessPercentFromUi())
        assertEquals(100, ConfigManager.getIconSizePercentFromUi())
    }

    @Test
    fun everyOnlineSetterMirrorsLocallyBeforeNotifyingExporters() {
        field("uiRemotePrefs").set(null, remote)
        val observed = mutableListOf<String>()
        listen { key ->
            if (key != null) {
                assertEquals("local and remote must agree before export", remote.all[key], local.all[key])
                assertFalse(local.getStringSet("pending_remote_keys", emptySet())!!.contains(key))
                observed += key
            }
        }

        ConfigManager.setEnabled(false)
        ConfigManager.setThemeId("test_theme")
        ConfigManager.setVariantId("  EXPRESSIVE  ")
        ConfigManager.setLawniconsEnabled(false)
        ConfigManager.setLawniconsEnabledForPackage("app.disabled", false)
        ConfigManager.setColorEngineId(" MATERIAL_3 ")
        ConfigManager.setCircleIconsEnabled(true)
        ConfigManager.setGeneratedArtworkRoundnessPercent(42)
        ConfigManager.setGeneratedArtworkFollowIconRoundness(true)
        ConfigManager.setNotificationIconsEnabled(false)
        ConfigManager.setLanguage("zh")

        assertEquals(
            listOf("enabled", "theme_id", "variant_id", "lawnicons_enabled", "lawnicons_disabled_packages", "color_engine_id", "circle_icons", "generated_artwork_roundness_percent", "generated_artwork_follow_icon_roundness", "notification_icons", "ui_language"),
            observed,
        )
        assertEquals("expressive", local.getString("variant_id", null))
        assertFalse(local.getBoolean("enabled", true))
        assertTrue(local.getBoolean("circle_icons", false))
        assertFalse(local.getBoolean("lawnicons_enabled", true))
        assertEquals(setOf("app.disabled"), local.getStringSet("lawnicons_disabled_packages", emptySet()))
        assertEquals("material_3", ConfigManager.getColorEngineId())
        assertTrue("legacy setter migrates notification icons to enabled", local.getBoolean("notification_icons", false))
        assertEquals("test_theme", local.getString("theme_id", null))
        assertEquals("zh", local.getString("ui_language", null))
    }

    @Test
    fun generatedArtworkAppearanceClampsPersistsAndResolvesPerAppOverrides() {
        assertEquals(0, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi())
        assertFalse(ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi())
        ConfigManager.setIconRoundnessPercent(40)
        ConfigManager.setGeneratedArtworkRoundnessPercent(130)
        ConfigManager.setGeneratedArtworkFollowIconRoundness(true)
        assertEquals(100, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi())
        assertTrue(ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi())
        assertEquals(0.35f, ConfigManager.getEffectiveIconStyleFromUi(null).generatedArtworkRadiusFraction, 0.0001f)

        val pkg = "com.example.artwork"
        ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(
            roundnessPercent = 80,
            generatedArtworkRoundnessPercent = -20,
            generatedArtworkFollowIconRoundness = false,
        ))
        val effective = ConfigManager.getEffectiveIconStyleFromUi(pkg)
        assertEquals(0, effective.generatedArtworkRoundnessPercent)
        assertFalse(effective.generatedArtworkFollowIconRoundness)
        assertEquals(0f, effective.generatedArtworkRadiusFraction, 0f)
        assertTrue(ConfigManager.getAppIconOverridesFromUi(pkg).hasOverrides)

        ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }.invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)
        assertEquals(local.all["generated_artwork_roundness_percent"],
            remote.all["generated_artwork_roundness_percent"])
        assertEquals(local.all["generated_artwork_follow_icon_roundness"],
            remote.all["generated_artwork_follow_icon_roundness"])
    }

    @Test
    fun delayedServiceBindPreservesOfflineEditsAndMirrorsExistingRemoteValues() {
        local.edit().putBoolean("circle_icons", true).putString("theme_id", "local_theme").commit()
        remote.edit().putBoolean("circle_icons", false).putString("variant_id", "tonal_spot").commit()
        ConfigManager.setVariantId("expressive")
        assertTrue(local.getStringSet("pending_remote_keys", emptySet())!!.contains("variant_id"))

        ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }
            .invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)

        assertEquals("expressive", ConfigManager.getVariantId())
        assertEquals("expressive", remote.getString("variant_id", null))
        assertFalse("existing framework values take precedence over an old local mirror", local.getBoolean("circle_icons", true))
        assertEquals("local_theme", remote.getString("theme_id", null))
        assertTrue(local.getStringSet("pending_remote_keys", emptySet())!!.isEmpty())
        assertTrue(local.getBoolean("notification_icons", false))
        assertTrue(remote.getBoolean("notification_icons", false))
    }

    @Test
    fun listenerFailuresAreIsolatedAndUnregistrationStopsCallbacks() {
        val observed = mutableListOf<String?>()
        listen { error("one failing consumer") }
        val listener: (String?) -> Unit = { observed += it }
        listen(listener)
        ConfigManager.setEnabled(false)
        ConfigManager.removeOnConfigChangedListener(listener)
        ConfigManager.setEnabled(true)
        assertEquals(listOf("enabled"), observed)
    }

    @Test
    fun offlinePerAppChoicesSurviveGlobalOffAndDelayedBind() {
        assertTrue("new matches are allowed by default", ConfigManager.isLawniconsEnabledForPackageFromUi("app.new"))
        ConfigManager.setLawniconsEnabledForPackage("app.disabled/.Main", false)
        ConfigManager.setLawniconsEnabledForPackage("app.other", false)
        val snapshot = ConfigManager.getLawniconsDisabledPackagesFromUi()
        ConfigManager.setLawniconsEnabled(false)
        assertTrue("global off retains app selection", ConfigManager.isLawniconsEnabledForPackageFromUi("app.new"))
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("app.new"))
        ConfigManager.setLawniconsEnabledForPackage("app.other", true)
        assertEquals("getter returns a stable copy", setOf("app.disabled", "app.other"), snapshot)
        ConfigManager.setColorEngineId("material_3")
        remote.edit().putStringSet("lawnicons_disabled_packages", setOf("app.remote")).commit()

        ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }.invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)
        assertEquals(setOf("app.disabled"), remote.getStringSet("lawnicons_disabled_packages", emptySet()))
        assertEquals("material_3", remote.getString("color_engine_id", null))
        ConfigManager.setLawniconsEnabled(true)
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("app.disabled"))
        assertTrue(ConfigManager.isLawniconsEnabledForPackage("app.other"))
        assertFalse(ConfigManager.isLawniconsEnabledForPackage("unknown"))
        ConfigManager.setColorEngineId("not_a_supported_engine")
        assertEquals("material_2025", ConfigManager.getColorEngineIdFromUi())
    }

    @Test
    fun borderPreferenceKeepsDefaultAndSurvivesOfflineEditsAndDelayedBinding() {
        assertEquals(1f, ConfigManager.generatedIconBorderRatio, 0f)
        ConfigManager.setGeneratedIconBorderRatio(0.37f)
        assertEquals(37, local.getInt("generated_icon_border_percent", -1))
        remote.edit().putInt("generated_icon_border_percent", 80).commit()
        ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }.invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)
        assertEquals(37, remote.getInt("generated_icon_border_percent", -1))
        assertEquals(0.37f, ConfigManager.generatedIconBorderRatio, 0.001f)
        ConfigManager.setGeneratedIconBorderRatio(-1f)
        assertEquals(0f, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f)
        ConfigManager.setGeneratedIconBorderRatio(Float.NaN)
        assertEquals(1f, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f)
        assertEquals(100, local.getInt("generated_icon_border_percent", -1))
        assertEquals(local.all["generated_icon_border_percent"], remote.all["generated_icon_border_percent"])
    }

    @Test
    fun freshSourceDefaultsToNativeAndExplicitLegacyChoicesRemainCompatible() {
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getDefaultIconSourceStyleFromUi())
        assertTrue(ConfigManager.permitsLawniconsFallback(null))
        assertFalse("reading defaults must not persist a user selection", local.contains("default_icon_source_style"))
        local.edit().putBoolean("lawnicons_enabled", true).commit()
        assertEquals(IconSourceStyle.LAWNICONS, ConfigManager.getDefaultIconSourceStyleFromUi())
        local.edit().putBoolean("lawnicons_enabled", false).commit()
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getDefaultIconSourceStyleFromUi())
        assertFalse(ConfigManager.permitsLawniconsFallback(null))
        for (style in IconSourceStyle.entries) {
            ConfigManager.setDefaultIconSourceStyle(style)
            ConfigManager.setLawniconsEnabled(true)
            assertEquals(style, ConfigManager.getDefaultIconSourceStyleFromUi())
            ConfigManager.setLawniconsEnabled(false)
            assertEquals(style, ConfigManager.getDefaultIconSourceStyleFromUi())
        }
    }

    @Test
    fun sourceAndPerAppOverridesSurviveOfflineBindingAndResetIndependently() {
        local.edit().putBoolean("lawnicons_enabled", false)
            .putStringSet("lawnicons_disabled_packages", setOf("com.example.old", "com.example.other")).commit()
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getDefaultIconSourceStyleFromUi())
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getAppIconOverridesFromUi("com.example.old").style)
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.GENERATED)
        ConfigManager.setIconRoundnessPercent(37)
        ConfigManager.setIconSizePercent(66)
        ConfigManager.setAppIconOverrides("com.example.old/Main", AppIconOverrides(sizePercent = 0, borderPercent = 0))
        ConfigManager::class.java.getDeclaredMethod("reconcileUiPreferences", SharedPreferences::class.java)
            .apply { isAccessible = true }.invoke(ConfigManager, remote)
        field("uiRemotePrefs").set(null, remote)
        assertEquals("generated", remote.getString("default_icon_source_style", null))
        assertEquals(local.all["app_icon_overrides"], remote.all["app_icon_overrides"])
        assertEquals(EffectiveIconStyle(IconSourceStyle.NATIVE_GENERATED, 37, 0, 0),
            ConfigManager.getEffectiveIconStyle("com.example.old/Main"))
        ConfigManager.setAppIconOverrides("com.example.old", AppIconOverrides())
        assertFalse(ConfigManager.getAppIconOverridesFromUi("com.example.old").hasOverrides)
        assertEquals(EffectiveIconStyle(IconSourceStyle.GENERATED, 37, 66, 100),
            ConfigManager.getEffectiveIconStyle("com.example.old"))
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getAppIconOverridesFromUi("com.example.other").style)
    }

    @Test
    fun geometryOnlyEditPreservesLegacyExclusionUntilStyleReset() {
        val pkg = "com.example.legacy"
        local.edit().putStringSet("lawnicons_disabled_packages", setOf(pkg)).commit()
        val original = ConfigManager.getAppIconOverridesFromUi(pkg)
        val draft = original.copy(roundnessPercent = 65)
        assertFalse(ConfigManager.permitsLawniconsFallbackFromUi(pkg, draft))
        ConfigManager.setAppIconOverrides(pkg, draft)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        assertTrue(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
        ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(roundnessPercent = 65))
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        assertTrue(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
        ConfigManager.setAppIconOverrides(pkg, draft, replaceLegacySource = true)
        assertFalse("explicit direct native style still skips Lawnicons after legacy migration",
            ConfigManager.permitsLawniconsFallback(pkg))
        assertFalse(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
        ConfigManager.setAppIconOverrides(pkg, draft.copy(style = null))
        assertTrue(ConfigManager.permitsLawniconsFallback(pkg))
        assertEquals(65, ConfigManager.getAppIconOverridesFromUi(pkg).roundnessPercent)
    }

    @Test
    fun savedLegacyNativeGeometryKeepsBundleExclusionUntilExplicitSourceSelection() {
        val pkg = "com.example.oldnative"
        for (previousStyle in listOf(IconSourceStyle.NATIVE, IconSourceStyle.LAWNICONS)) {
            local.edit().putStringSet("lawnicons_disabled_packages", setOf(pkg))
                .putString("app_icon_overrides", AppIconOverridesCodec.encode(mapOf(
                    pkg to AppIconOverrides(style = previousStyle, sizePercent = 42))))
                .commit()
            val migrated = ConfigManager.getAppIconOverridesFromUi(pkg)
            assertEquals(IconSourceStyle.NATIVE_GENERATED, migrated.style)
            assertEquals(42, migrated.sizePercent)
            assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
            assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
            assertFalse(ConfigManager.permitsLawniconsFallbackFromUi(pkg, migrated))
            ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(style = previousStyle, sizePercent = 55))
            assertTrue("editing old saved geometry alone retains its bundle exclusion",
                pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
            assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
            ConfigManager.setAppIconOverrides(pkg, migrated.copy(roundnessPercent = 65))
            assertTrue(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
            assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
            ConfigManager.setAppIconOverrides(pkg, migrated.copy(style = previousStyle), replaceLegacySource = true)
            assertEquals(previousStyle, ConfigManager.getEffectiveIconStyle(pkg).style)
            assertTrue(ConfigManager.permitsLawniconsFallback(pkg))
            assertFalse(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
        }
    }

    @Test
    fun authoredFallbackStylesPermitBundleLookupForDefaultsAndPerAppDrafts() {
        val pkg = "com.example.source"
        for (style in IconSourceStyle.entries) {
            ConfigManager.setDefaultIconSourceStyle(style)
            assertEquals(style.permitsLawniconsFallback, ConfigManager.permitsLawniconsFallback(pkg))
            assertEquals(style.permitsLawniconsFallback,
                ConfigManager.permitsLawniconsFallbackFromUi(pkg, AppIconOverrides()))
            for (override in IconSourceStyle.entries) {
                val draft = AppIconOverrides(style = override)
                assertEquals(override.permitsLawniconsFallback,
                    ConfigManager.permitsLawniconsFallbackFromUi(pkg, draft))
                ConfigManager.setAppIconOverrides(pkg, draft)
                assertEquals(override, ConfigManager.getEffectiveIconStyle(pkg).style)
                assertEquals(override.permitsLawniconsFallback, ConfigManager.permitsLawniconsFallback(pkg))
            }
            ConfigManager.setAppIconOverrides(pkg, AppIconOverrides())
        }
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.LAWNICONS)
        ConfigManager.setLawniconsEnabledForPackage(pkg, false)
        assertEquals(IconSourceStyle.NATIVE_GENERATED, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertFalse(ConfigManager.permitsLawniconsFallback(pkg))
        assertTrue("resetting the source draft follows Lawnicons and will replace the legacy exclusion",
            ConfigManager.permitsLawniconsFallbackFromUi(pkg, AppIconOverrides()))
        ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(style = IconSourceStyle.NATIVE), replaceLegacySource = true)
        assertEquals(IconSourceStyle.NATIVE, ConfigManager.getEffectiveIconStyle(pkg).style)
        assertTrue("explicit native chain replaces the legacy bundle exclusion", ConfigManager.permitsLawniconsFallback(pkg))
        assertFalse(pkg in ConfigManager.getLawniconsDisabledPackagesFromUi())
    }

    @Test
    fun drawableAndLargeIconCloneKeepPackageGeometryAndFollowOnlyItsOverrides() {
        val pkg = "com.example.geometry"
        ConfigManager.setIconRoundnessPercent(0)
        ConfigManager.setIconSizePercent(0)
        ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(roundnessPercent = 100, sizePercent = 100))
        val mask = Bitmap.createBitmap(64, 64, Bitmap.Config.ALPHA_8)
        val drawable = ThemedIconBuilder.drawable(ThemedIconBuilder.Recipe(mask, 0, Color.RED,
            MaskStrategy.SOURCE_NATIVE, identity = "$pkg/Main")).drawable
        val clone = drawable.forLargeIcon(200, 200).constantState.newDrawable()
        fun draw(): Bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
            clone.setBounds(0, 0, 200, 200)
            clone.draw(Canvas(it))
        }
        try {
            draw().also { assertEquals(0, Color.alpha(it.getPixel(40, 4))); it.recycle() }
            ConfigManager.setIconRoundnessPercent(70)
            draw().also { assertEquals(0, Color.alpha(it.getPixel(40, 4))); it.recycle() }
            ConfigManager.setAppIconOverrides(pkg, AppIconOverrides(roundnessPercent = 0, sizePercent = 100))
            draw().also { assertEquals(255, Color.alpha(it.getPixel(40, 4))); it.recycle() }
        } finally { mask.recycle() }
    }

    private fun listen(listener: (String?) -> Unit) {
        listeners += listener
        ConfigManager.addOnConfigChangedListener(listener)
    }

    @Suppress("UNCHECKED_CAST")
    private fun serviceState() = field("mutableServiceState").get(null) as MutableStateFlow<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun configListeners() = field("configListeners").get(null) as MutableSet<(String?) -> Unit>

    private fun field(name: String) = ConfigManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }
}
