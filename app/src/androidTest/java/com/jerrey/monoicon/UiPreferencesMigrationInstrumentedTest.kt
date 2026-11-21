package com.jerrey.monoicon

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.ui.settings.UiPreferences
import com.jerrey.monoicon.ui.settings.UiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Older manager opt-outs migrate without resetting independently chosen settings. */
@RunWith(AndroidJUnit4::class)
class UiPreferencesMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var isolatedContext: Context
    private lateinit var preferencesName: String
    private lateinit var preferences: SharedPreferences
    private var savedPreferences: SharedPreferences? = null
    private lateinit var savedSettings: UiSettings
    private val retiredKeys = listOf("check_update", "monet", "blur", "floating_bar", "glass_bar")

    @Before
    fun isolateManagerPreferences() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        preferencesName = "ui_preferences_migration_test_${UUID.randomUUID()}"
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        isolatedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "manager_ui") preferences else super.getSharedPreferences(name, mode)
        }
        savedPreferences = uiField("preferences").get(null) as SharedPreferences?
        savedSettings = uiState().value
        uiField("preferences").set(null, null)
        uiState().value = UiSettings()
    }

    @After
    fun restoreManagerPreferences() {
        uiField("preferences").set(null, savedPreferences)
        uiState().value = savedSettings
        preferences.edit().clear().commit()
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun legacyOptOutsAreRemovedAndRemainEnabledAfterUpdatesAndReload() {
        val original = UiSettings(
            themeMode = 2,
            keyColor = 0xFF8844AA.toInt(),
            colorStyle = "Vibrant",
            colorSpec = "Spec2021",
            navigationBadge = false,
            pageScale = 0.9f,
        )
        assertTrue(preferences.edit().apply {
            retiredKeys.forEach { putBoolean(it, false) }
            putInt("theme_mode", original.themeMode)
            putInt("key_color", original.keyColor)
            putString("color_style", original.colorStyle)
            putString("color_spec", original.colorSpec)
            putBoolean("navigation_badge", original.navigationBadge)
            putFloat("page_scale", original.pageScale)
        }.commit())

        UiPreferences.init(isolatedContext)

        assertEquals(original, UiPreferences.state.value)
        assertAlwaysEnabled(UiPreferences.state.value)
        assertRetiredKeysAbsent()
        assertEquals(original.themeMode, preferences.getInt("theme_mode", -1))
        assertEquals(original.pageScale, preferences.getFloat("page_scale", 0f), 0f)
        assertFalse(preferences.getBoolean("navigation_badge", true))

        val updated = original.copy(themeMode = 1, pageScale = 1.05f, navigationBadge = true)
        UiPreferences.update {
            it.copy(themeMode = updated.themeMode, pageScale = updated.pageScale, navigationBadge = updated.navigationBadge)
        }

        assertEquals(updated, UiPreferences.state.value)
        assertAlwaysEnabled(UiPreferences.state.value)
        assertRetiredKeysAbsent()
        // Flush pending apply writes before exercising a fresh manager init.
        assertTrue(preferences.edit().commit())
        uiField("preferences").set(null, null)
        uiState().value = UiSettings()
        UiPreferences.init(isolatedContext)

        assertEquals(updated, UiPreferences.state.value)
        assertAlwaysEnabled(UiPreferences.state.value)
        assertRetiredKeysAbsent()
    }

    private fun assertAlwaysEnabled(settings: UiSettings) {
        assertTrue(settings.checkUpdate)
        assertTrue(settings.monet)
        assertTrue(settings.enableBlur)
        assertTrue(settings.floatingBottomBar)
        assertTrue(settings.glassBottomBar)
    }

    private fun assertRetiredKeysAbsent() {
        retiredKeys.forEach { assertFalse("Retired preference $it must not be saved", preferences.contains(it)) }
    }

    private fun uiField(name: String) = UiPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun uiState() = uiField("mutableState").get(null) as MutableStateFlow<UiSettings>
}
