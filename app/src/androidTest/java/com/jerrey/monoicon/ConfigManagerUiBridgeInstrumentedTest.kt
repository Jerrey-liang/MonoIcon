package com.jerrey.monoicon

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.render.IconShape
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The native-launcher exporter uses the same getters as in-process icon rendering. */
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
        // Application registers the native publisher in production. Preference
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
        assertFalse(ConfigManager.isNotificationIconsEnabled())
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
        ConfigManager.setCircleIconsEnabled(true)
        ConfigManager.setNotificationIconsEnabled(false)
        ConfigManager.setLanguage("zh")

        assertEquals(
            listOf("enabled", "theme_id", "variant_id", "lawnicons_enabled", "circle_icons", "notification_icons", "ui_language"),
            observed,
        )
        assertEquals("expressive", local.getString("variant_id", null))
        assertFalse(local.getBoolean("enabled", true))
        assertTrue(local.getBoolean("circle_icons", false))
        assertFalse(local.getBoolean("lawnicons_enabled", true))
        assertFalse(local.getBoolean("notification_icons", true))
        assertEquals("test_theme", local.getString("theme_id", null))
        assertEquals("zh", local.getString("ui_language", null))
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
