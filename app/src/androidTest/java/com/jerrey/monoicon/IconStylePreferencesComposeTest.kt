package com.jerrey.monoicon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.screens.LawniconsApp
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.MonoIconApp
import com.jerrey.monoicon.ui.screens.IconStyleScreen
import com.jerrey.monoicon.ui.screens.KernelPage
import com.jerrey.monoicon.ui.screens.LawniconsAppsScreen
import com.jerrey.monoicon.ui.settings.UiPreferences
import com.jerrey.monoicon.ui.settings.UiSettings
import com.jerrey.monoicon.ui.settings.AppUpdateChecker
import com.jerrey.monoicon.ui.settings.UpdateState
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.theme.MonoIconTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** App source and colour choices must persist without changing navigation. */
@RunWith(AndroidJUnit4::class)
class IconStylePreferencesComposeTest {
    private val strings = stringsFor(AppLanguage.ENGLISH, false)

    @get:Rule(order = 0)
    val isolatedSettings = object : ExternalResource() {
        private val savedConfig = mutableMapOf<String, Any?>()
        private var savedListeners = emptySet<(String?) -> Unit>()
        private var savedUiPreferences: SharedPreferences? = null
        private lateinit var savedUiSettings: UiSettings
        private var savedUpdateChecked = false
        private lateinit var savedUpdateState: UpdateState

        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            savedListeners = configListeners().toSet()
            configListeners().clear()
            val local = context.getSharedPreferences("icon_style_compose_test", Context.MODE_PRIVATE)
            local.edit().clear().putString("ui_language", "en").commit()
            for (name in listOf("fallbackPrefs", "uiRemotePrefs", "remotePrefsProvider")) {
                savedConfig[name] = configField(name).get(null)
                configField(name).set(null, if (name == "fallbackPrefs") local else null)
            }
            savedConfig["service"] = serviceState().value
            serviceState().value = null
            UiPreferences.init(context)
            savedUiPreferences = uiField("preferences").get(null) as SharedPreferences?
            savedUiSettings = uiState().value
            val ui = context.getSharedPreferences("icon_style_manager_compose_test", Context.MODE_PRIVATE)
            ui.edit().clear().commit()
            uiField("preferences").set(null, ui)
            uiState().value = UiSettings()
            savedUpdateChecked = updateField("checkedThisSession").getBoolean(null)
            savedUpdateState = updateState().value
            updateField("checkedThisSession").setBoolean(null, true)
            updateState().value = UpdateState()
        }

        override fun after() {
            // The Compose rule has disposed all listeners before this runs.
            savedConfig.forEach { (name, value) ->
                if (name == "service") serviceState().value = value else configField(name).set(null, value)
            }
            configListeners().addAll(savedListeners)
            uiField("preferences").set(null, savedUiPreferences)
            uiState().value = savedUiSettings
            updateField("checkedThisSession").setBoolean(null, savedUpdateChecked)
            updateState().value = savedUpdateState
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun appSettingsEntryIsAlwaysAvailableAndReturnsToTheStyleTab() {
        ConfigManager.setLawniconsEnabled(false)
        composeRule.setContent { MonoIconApp() }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tabRole and androidx.compose.ui.test.hasText(strings.iconStyle)).performClick()
        composeRule.onNodeWithTag("open-app-icon-settings").performScrollTo().performClick()
        composeRule.onNodeWithTag("app-icon-settings-page").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(strings.back).performClick()
        composeRule.onNode(tabRole and androidx.compose.ui.test.hasText(strings.iconStyle)).assertIsDisplayed()
        composeRule.onNodeWithTag("open-app-icon-settings").performScrollTo().performClick()
        pressBack()
        composeRule.onNode(tabRole and androidx.compose.ui.test.hasText(strings.iconStyle)).assertIsDisplayed()
    }

    @Test
    fun allFourDefaultStylesPersist() {
        showStyleScreen()
        for ((label, style) in listOf(
            "App native" to com.jerrey.monoicon.config.IconSourceStyle.NATIVE,
            "Native → MonoIcon" to com.jerrey.monoicon.config.IconSourceStyle.NATIVE_GENERATED,
            "MonoIcon generated" to com.jerrey.monoicon.config.IconSourceStyle.GENERATED,
            "Lawnicons" to com.jerrey.monoicon.config.IconSourceStyle.LAWNICONS,
        )) {
            composeRule.onNodeWithTag("default-icon-style").performScrollTo().performClick()
            composeRule.onNodeWithText(label).performClick()
            composeRule.runOnIdle { assertEquals(style, ConfigManager.getDefaultIconSourceStyleFromUi()) }
        }
    }

    @Test
    fun bothColourEnginesAreSelectableAndKeepThePaletteVariant() {
        assertEquals("material_2025", ConfigManager.getColorEngineIdFromUi())
        showStyleScreen()
        composeRule.onNodeWithTag("icon-color-engine").performScrollTo().performClick()
        composeRule.onNodeWithText(strings.colorEngineMaterial3).performClick()
        composeRule.runOnIdle { assertEquals("material_3", ConfigManager.getColorEngineIdFromUi()) }
        composeRule.onNodeWithTag("icon-color-style").performScrollTo().performClick()
        composeRule.onNodeWithText(strings.colorVariants[3]).performClick()
        composeRule.onNodeWithTag("icon-color-engine").performScrollTo().performClick()
        composeRule.onNodeWithText(strings.colorEngine2025).performClick()
        composeRule.runOnIdle {
            assertEquals("material_2025", ConfigManager.getColorEngineIdFromUi())
            assertEquals("vibrant", ConfigManager.getVariantIdFromUi())
        }
    }

    @Test
    fun styleTabKeepsItsPreviewAboveTheScrollingSettings() {
        composeRule.setContent { MonoIconApp() }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tabRole and androidx.compose.ui.test.hasText(strings.iconStyle)).performClick()
        composeRule.onNodeWithTag("icon-color-style").performScrollTo()
        composeRule.onNodeWithTag("pinned-preview-host").assertIsDisplayed()
        composeRule.onNodeWithTag("icon-style-preview").assertIsDisplayed()
        val preview = composeRule.onNodeWithTag("pinned-preview-host").fetchSemanticsNode().boundsInRoot
        val style = composeRule.onNodeWithTag("icon-color-style").fetchSemanticsNode().boundsInRoot
        assertTrue("Scrolling the settings keeps the preview above them", preview.bottom <= style.top)
    }

    @Test
    fun eachParameterHasItsOwnPageWithLivePreviewAboveTheSlider() {
        // Every example on this page must actually use the adjustable generated mask.
        ConfigManager.setDefaultIconSourceStyle(com.jerrey.monoicon.config.IconSourceStyle.GENERATED)
        composeRule.setContent { MonoIconApp() }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tabRole and androidx.compose.ui.test.hasText(strings.iconStyle)).performClick()
        for ((tag, value) in listOf("icon-roundness" to 37, "icon-size" to 62, "icon-border-size" to 24, "artwork-roundness" to 81)) {
            composeRule.onNodeWithTag("open-$tag").performScrollTo().performClick()
            composeRule.onNodeWithTag("$tag-page").assertIsDisplayed()
            composeRule.onNodeWithTag("icon-style-preview").assertIsDisplayed()
            val slider = composeRule.onNodeWithTag(tag).performScrollTo()
            val previewBottom = composeRule.onNodeWithTag("pinned-preview-host").fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("The pinned preview is above the controls", previewBottom <= slider.fetchSemanticsNode().boundsInRoot.top)
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(value.toFloat()) }
            slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(value.toFloat(), 0f..100f)))
            composeRule.waitUntil(5_000) {
                composeRule.onNodeWithTag("icon-style-preview").fetchSemanticsNode()
                    .config[SemanticsProperties.StateDescription].contains(strings.iconBorderSize)
            }
            composeRule.onNodeWithTag("icon-style-preview").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, strings.iconRoundness + ": ${ConfigManager.getIconRoundnessPercentFromUi()}%, " +
                    strings.iconSize + ": ${ConfigManager.getIconSizePercentFromUi()}%, " +
                    strings.iconBorderSize + ": ${Math.round(ConfigManager.getGeneratedIconBorderRatioFromUi() * 100)}%, " +
                    "Artwork roundness: ${ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()}%"))
            composeRule.onNodeWithContentDescription(strings.back).performClick()
        }
        composeRule.runOnIdle {
            assertEquals(37, ConfigManager.getIconRoundnessPercentFromUi())
            assertEquals(62, ConfigManager.getIconSizePercentFromUi())
            assertEquals(0.24f, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f)
            assertEquals(81, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi())
        }
    }

    @Test
    fun artworkEndpointsPersistAndFollowingTemporarilyDisablesIndependentControl() {
        ConfigManager.setDefaultIconSourceStyle(com.jerrey.monoicon.config.IconSourceStyle.GENERATED)
        val parameter = mutableStateOf(com.jerrey.monoicon.ui.screens.IconStyleParameter.ARTWORK_ROUNDNESS)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    com.jerrey.monoicon.ui.screens.IconStyleParameterScreen(parameter.value, {})
                }
            }
        }
        val artwork = composeRule.onNodeWithTag("artwork-roundness").performScrollTo()
        artwork.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(0, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()) }
        artwork.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        composeRule.runOnIdle { assertEquals(100, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()) }
        composeRule.runOnUiThread { parameter.value = com.jerrey.monoicon.ui.screens.IconStyleParameter.ROUNDNESS }
        composeRule.onNodeWithTag("artwork-follow-icon-roundness").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi()) }
        composeRule.runOnUiThread { parameter.value = com.jerrey.monoicon.ui.screens.IconStyleParameter.ARTWORK_ROUNDNESS }
        composeRule.onNodeWithTag("artwork-roundness").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Following icon roundness; turn off following to adjust").assertIsDisplayed()
        composeRule.runOnUiThread { ConfigManager.setGeneratedArtworkFollowIconRoundness(false) }
        composeRule.onNodeWithTag("artwork-roundness").assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(100f, 0f..100f, 0)))
        composeRule.runOnIdle { assertEquals(100, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()) }
    }

    @Test
    fun compactSubpageKeepsThePinnedPreviewAndSliderReachable() {
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        com.jerrey.monoicon.ui.screens.IconStyleParameterScreen(
                            com.jerrey.monoicon.ui.screens.IconStyleParameter.SIZE, {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("icon-size").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(55f) }
        composeRule.onNodeWithTag("pinned-preview-host").assertIsDisplayed()
        composeRule.onNodeWithTag("icon-size").assertIsDisplayed()
        val preview = composeRule.onNodeWithTag("pinned-preview-host").fetchSemanticsNode().boundsInRoot
        val controls = composeRule.onNodeWithTag("kernel-page-scroll").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.height > 0 && controls.height > 0)
        assertTrue(preview.bottom <= controls.top)
        composeRule.runOnIdle { assertEquals(55, ConfigManager.getIconSizePercentFromUi()) }
    }

    @Test
    fun borderEndpointsAndExternalUpdatesRefreshTheSubpage() {
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    com.jerrey.monoicon.ui.screens.IconStyleParameterScreen(
                        com.jerrey.monoicon.ui.screens.IconStyleParameter.BORDER, {})
                }
            }
        }
        val border = composeRule.onNodeWithTag("icon-border-size").performScrollTo()
        border.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        composeRule.runOnIdle { assertEquals(0f, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f) }
        border.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        composeRule.runOnIdle { assertEquals(1f, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f) }
        composeRule.runOnUiThread { ConfigManager.setGeneratedIconBorderRatio(0.43f) }
        border.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(43f, 0f..100f)))
    }

    private fun showStyleScreen() {
        val variant = mutableStateOf("tonal_spot")
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    KernelPage(title = strings.iconStyle) {
                        IconStyleScreen(variant.value, { variant.value = it })
                    }
                }
            }
        }
    }
    private fun configField(name: String) = ConfigManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun uiField(name: String) = UiPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun updateField(name: String) = AppUpdateChecker::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun configListeners() = configField("configListeners").get(null) as MutableSet<(String?) -> Unit>

    @Suppress("UNCHECKED_CAST")
    private fun serviceState() = configField("mutableServiceState").get(null) as MutableStateFlow<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun uiState() = uiField("mutableState").get(null) as MutableStateFlow<UiSettings>

    @Suppress("UNCHECKED_CAST")
    private fun updateState() = updateField("mutableState").get(null) as MutableStateFlow<UpdateState>
}
