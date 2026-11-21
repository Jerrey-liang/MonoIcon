package com.jerrey.monoicon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.screens.LawniconsApp
import com.jerrey.monoicon.ui.screens.LawniconsAppsScreen
import com.jerrey.monoicon.ui.screens.AppIconSettingsScreen
import com.jerrey.monoicon.ui.screens.IconStyleExample
import com.jerrey.monoicon.ui.screens.resolvedAppIconSource
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.ui.settings.UiPreferences
import com.jerrey.monoicon.ui.settings.UiSettings
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class PerAppIconSettingsComposeTest {
    private val strings = stringsFor(AppLanguage.ENGLISH, false)
    private val first = LawniconsApp("com.example.alpha", "Alpha 相机", "com.example.alpha/.MainActivity", true, false)
    private val second = LawniconsApp("com.example.beta", "Beta", "com.example.beta/.MainActivity", false, true)

    @get:Rule(order = 0)
    val isolatedSettings = object : ExternalResource() {
        private val savedConfig = mutableMapOf<String, Any?>()
        private var savedListeners = emptySet<(String?) -> Unit>()
        private var savedUiPreferences: SharedPreferences? = null
        private lateinit var savedUiSettings: UiSettings

        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            savedListeners = configListeners().toSet()
            configListeners().clear()
            val local = context.getSharedPreferences("per_app_icon_compose_test", Context.MODE_PRIVATE)
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
            val ui = context.getSharedPreferences("per_app_icon_manager_compose_test", Context.MODE_PRIVATE)
            ui.edit().clear().commit()
            uiField("preferences").set(null, ui)
            uiState().value = UiSettings()
        }

        override fun after() {
            savedConfig.forEach { (name, value) ->
                if (name == "service") serviceState().value = value else configField(name).set(null, value)
            }
            configListeners().addAll(savedListeners)
            uiField("preferences").set(null, savedUiPreferences)
            uiState().value = savedUiSettings
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun showApps(
        load: suspend (Context) -> List<LawniconsApp> = { listOf(first, second) },
        loadOriginal: suspend (Context, LawniconsApp) -> Bitmap? = { _, _ -> null },
        onOpenApp: (LawniconsApp) -> Unit = {},
        compact: Boolean = false,
    ) {
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Box(if (compact) Modifier.fillMaxWidth().height(300.dp) else Modifier) {
                        LawniconsAppsScreen(onBack = {}, loadApps = load, loadOriginalIcon = loadOriginal,
                            onOpenApp = onOpenApp)
                    }
                }
            }
        }
    }

    private fun showDetail(
        app: LawniconsApp = first,
        rendered: MutableList<EffectiveIconStyle> = CopyOnWriteArrayList(),
        loaded: MutableList<Pair<IconSourceStyle, Boolean>> = CopyOnWriteArrayList(),
        compact: Boolean = false,
        loadResources: suspend (Context, LawniconsApp) -> LawniconsApp = { _, value -> value },
        actualSource: (suspend (LawniconsApp, IconSourceStyle, Boolean) -> Int)? = null,
        renderFailure: (() -> Boolean)? = null,
    ) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        var selectedStyle = IconSourceStyle.NATIVE
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Box(if (compact) Modifier.fillMaxWidth().height(340.dp) else Modifier) {
                        AppIconSettingsScreen(
                            app = app,
                            onBack = {},
                            loadAppResources = loadResources,
                            loadExample = { _, selectedApp, style, fallback ->
                                selectedStyle = style
                                loaded += style to fallback
                                val source = actualSource?.invoke(selectedApp, style, fallback) ?: when (
                                    resolvedAppIconSource(selectedApp, style, fallback)
                                ) {
                                    IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED -> MaskStrategy.SOURCE_NATIVE
                                    IconSourceStyle.LAWNICONS -> MaskStrategy.SOURCE_LAWNICONS
                                    IconSourceStyle.GENERATED -> MaskStrategy.SOURCE_AOSP_LEGACY
                                }
                                // Even a malformed loader returning extra samples must show just this app.
                                listOf(IconStyleExample(selectedApp.identity, selectedApp.label, bitmap,
                                    source == MaskStrategy.SOURCE_LAWNICONS, source = source),
                                    IconStyleExample("unrelated", "Other app", bitmap, false, source = MaskStrategy.SOURCE_AOSP_LEGACY))
                            },
                            renderExamples = { examples, _, roundness, size, border ->
                                if (renderFailure?.invoke() == true) error("Temporary render failure")
                                rendered += EffectiveIconStyle(selectedStyle, roundness, size, (border * 100).toInt())
                                examples.map { bitmap }
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-${app.identity}").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitLoaded() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("app-icon-result-count").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openSearch() {
        composeRule.onNodeWithTag("app-icon-search-open").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("app-icon-search").assertIsDisplayed()
    }

    @Test
    fun cardsAreDistinctAndSearchMatchesLabelOrPackageWithoutCaseSensitivity() {
        showApps(load = { listOf(first, first.copy(identity = "duplicate"), second) })
        awaitLoaded()
        composeRule.onAllNodesWithTag("app-icon-card-${first.packageName}").assertCountEquals(1)
        openSearch()
        composeRule.onNodeWithTag("app-icon-search").performTextInput("相机")
        composeRule.onNodeWithText("1 app").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-card-${first.packageName}").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-search").performTextClearance()
        composeRule.onNodeWithTag("app-icon-search").performTextInput("COM.EXAMPLE.BETA")
        composeRule.onNodeWithTag("app-icon-card-${second.packageName}").assertIsDisplayed()
        composeRule.onAllNodesWithTag("app-icon-card-${first.packageName}").assertCountEquals(0)
    }

    @Test
    fun artworkRoundnessAndFollowingInheritIndependentlyAndResetTogether() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.GENERATED)
        ConfigManager.setGeneratedArtworkRoundnessPercent(28)
        ConfigManager.setGeneratedArtworkFollowIconRoundness(true)
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(generatedArtworkRoundnessPercent = 63))
        showDetail()
        val artworkTag = "app-artwork-roundness-${first.packageName}"
        val followTag = "app-artwork-follow-icon-roundness-${first.packageName}"
        composeRule.onNodeWithTag(artworkTag).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("$followTag-mode").performScrollTo().performClick()
        composeRule.onNode(androidx.compose.ui.test.hasText("Custom") and
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, false)).performClick()
        composeRule.onNodeWithTag(followTag).performScrollTo().performClick()
        composeRule.onNodeWithTag(artworkTag).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(77f) }
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(generatedArtworkRoundnessPercent = 77, generatedArtworkFollowIconRoundness = false),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
            ConfigManager.setGeneratedArtworkRoundnessPercent(42)
        }
        composeRule.runOnIdle {
            val style = ConfigManager.getEffectiveIconStyleFromUi(first.packageName)
            assertEquals(77, style.generatedArtworkRoundnessPercent)
            assertEquals(false, style.generatedArtworkFollowIconRoundness)
        }
        composeRule.onNodeWithTag("$followTag-mode").performScrollTo().performClick()
        composeRule.onNode(androidx.compose.ui.test.hasText("Follow default") and
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, false)).performClick()
        composeRule.onNodeWithTag(artworkTag).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("app-icon-reset-${first.packageName}").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(), ConfigManager.getAppIconOverridesFromUi(first.packageName))
            assertEquals(42, ConfigManager.getEffectiveIconStyleFromUi(first.packageName).generatedArtworkRoundnessPercent)
        }
    }

    @Test
    fun artworkControlsFollowTheConfirmedNativeOrGeneratedSource() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.NATIVE)
        val loaded = CopyOnWriteArrayList<Pair<IconSourceStyle, Boolean>>()
        showDetail(loaded = loaded)
        composeRule.onNodeWithTag("app-icon-roundness-${first.packageName}-mode").performScrollTo()
        composeRule.onAllNodesWithTag("app-artwork-follow-icon-roundness-${first.packageName}-mode").assertCountEquals(0)
        composeRule.onAllNodesWithTag("app-artwork-roundness-${first.packageName}-mode").assertCountEquals(0)
        composeRule.runOnUiThread { ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.GENERATED) }
        composeRule.waitUntil(5_000) { loaded.lastOrNull()?.first == IconSourceStyle.GENERATED }
        composeRule.onNodeWithTag("app-artwork-roundness-${first.packageName}-mode").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun styleAndNumericEditsPersistAndRefreshTheSingleIconPreview() {
        val calls = CopyOnWriteArrayList<EffectiveIconStyle>()
        showDetail(rendered = calls)
        composeRule.onNodeWithTag("app-icon-detail-restart").assertIsDisplayed()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("MonoIcon generated").performClick()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(3)
        composeRule.onNodeWithTag("app-icon-size-${first.packageName}-mode").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom").performClick()
        composeRule.onNodeWithTag("app-icon-size-${first.packageName}").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(37f) }
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(style = IconSourceStyle.GENERATED, sizePercent = 37),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
        composeRule.waitUntil(5_000) {
            calls.any { it.style == IconSourceStyle.GENERATED && it.sizePercent == 37 }
        }
        composeRule.onNodeWithTag("icon-preview-${first.identity}").assertIsDisplayed()
        composeRule.onAllNodesWithTag("icon-preview-unrelated").assertCountEquals(0)
        val previewBottom = composeRule.onNodeWithTag("pinned-preview-host").fetchSemanticsNode().boundsInRoot.bottom
        val controlsTop = composeRule.onNodeWithTag("kernel-page-scroll").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Preview remains above the scrollable controls", previewBottom <= controlsTop)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(5)
        composeRule.onNodeWithTag("app-icon-reset-${first.packageName}").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(AppIconOverrides(), ConfigManager.getAppIconOverridesFromUi(first.packageName)) }
        composeRule.waitUntil(5_000) { calls.lastOrNull() == ConfigManager.getEffectiveIconStyleFromUi(null) }
    }

    @Test
    fun listShowsOriginalIconsAndOpensTheSelectedApp() {
        val originals = CopyOnWriteArrayList<String>()
        var selected: LawniconsApp? = null
        showApps(loadOriginal = { _, app ->
            originals += app.identity
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        }, onOpenApp = { selected = it })
        awaitLoaded()
        composeRule.waitUntil(5_000) { first.identity in originals }
        val originalIcon = composeRule.onNodeWithContentDescription("${first.label}, original app icon")
        originalIcon.assertIsDisplayed()
        val iconWidthDp = originalIcon
            .fetchSemanticsNode().boundsInRoot.width /
            InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals(40f, iconWidthDp, 0.5f)
        composeRule.onNodeWithTag("app-icon-list-restart").assertIsDisplayed()
        composeRule.onAllNodesWithTag("icon-style-preview").assertCountEquals(0)
        composeRule.onNodeWithTag("app-icon-edit-${first.packageName}").performClick()
        composeRule.runOnIdle { assertEquals(first, selected) }
    }

    @Test
    fun inheritedValuesUpdateWhileCustomValuesAndLegacyExclusionArePreserved() {
        ConfigManager.setLawniconsEnabledForPackage(first.packageName, false)
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(borderPercent = 24))
        val calls = CopyOnWriteArrayList<EffectiveIconStyle>()
        val sources = CopyOnWriteArrayList<Pair<IconSourceStyle, Boolean>>()
        showDetail(rendered = calls, loaded = sources)
        composeRule.runOnIdle { ConfigManager.setIconSizePercent(61) }
        composeRule.waitUntil(5_000) { calls.lastOrNull()?.let { it.sizePercent == 61 && it.borderPercent == 24 } == true }
        assertEquals(IconSourceStyle.NATIVE_GENERATED to false, sources.last())
        composeRule.onNodeWithText("Custom setting; currently matches the default style", substring = true)
            .assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(style = IconSourceStyle.NATIVE_GENERATED, borderPercent = 24),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("Follow default (App native)").assertIsDisplayed()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onAllNodesWithText("Native → MonoIcon").assertCountEquals(0)
        // Miuix invokes the item callback even though the folded override is displayed as index zero.
        composeRule.onNodeWithText("Follow default (App native)").performClick()
        composeRule.runOnIdle {
            assertTrue(first.packageName !in ConfigManager.getLawniconsDisabledPackagesFromUi())
            assertEquals(AppIconOverrides(borderPercent = 24), ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
    }

    @Test
    fun compactDetailKeepsPreviewAboveScrollableParameters() {
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.GENERATED, borderPercent = 72))
        showDetail(compact = true)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(19f) }
        composeRule.runOnIdle { assertEquals(19, ConfigManager.getAppIconOverridesFromUi(first.packageName).borderPercent) }
        val preview = composeRule.onNodeWithTag("pinned-preview-host").fetchSemanticsNode().boundsInRoot
        val controls = composeRule.onNodeWithTag("kernel-page-scroll").fetchSemanticsNode().boundsInRoot
        assertTrue("Controls retain space below preview", preview.bottom <= controls.top && controls.height > 0)
    }

    @Test
    fun detailOffersOnlySourcesPresentInTheApp() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.GENERATED)
        showDetail(app = first.copy(hasNativeMonochrome = false, hasLawnicons = false))
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onAllNodesWithText("Native → MonoIcon").assertCountEquals(0)
        composeRule.onAllNodesWithText("Lawnicons").assertCountEquals(0)
        composeRule.onNodeWithText("Follow default (MonoIcon generated)").assertIsDisplayed()
        composeRule.onAllNodesWithText("MonoIcon generated").assertCountEquals(0)
    }

    @Test
    fun lawniconsOnlyDropdownSavesTheVisibleSourceInsteadOfTheUnfilteredIndex() {
        // Pin a non-Lawnicons default so Lawnicons remains an explicit visible choice.
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.NATIVE_GENERATED)
        showDetail(app = second)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${second.packageName}").performScrollTo().performClick()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onAllNodesWithText("Native → MonoIcon").assertCountEquals(0)
        composeRule.onNodeWithText("Follow default (MonoIcon generated)").assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText("Lawnicons").assertIsNotSelected().performClick()
        composeRule.runOnIdle {
            assertEquals(IconSourceStyle.LAWNICONS, ConfigManager.getAppIconOverridesFromUi(second.packageName).style)
        }
    }

    @Test
    fun nativeFirstDefaultFallsThroughToLawniconsAndRemovesItsDuplicateChoice() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.NATIVE)
        showDetail(app = second)
        composeRule.onNodeWithText("Current source: Lawnicons").assertIsDisplayed()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${second.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("Follow default (Lawnicons)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Lawnicons").assertCountEquals(0)
        composeRule.onNodeWithText("MonoIcon generated").assertIsDisplayed()
    }

    @Test
    fun generatedDefaultKeepsBothNativeStrategiesAndSelectsTheStoredOne() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.GENERATED)
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.NATIVE_GENERATED))
        showDetail(app = first)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("Follow default (MonoIcon generated)").assertIsDisplayed()
        composeRule.onNodeWithText("App native").assertIsDisplayed().assertIsNotSelected()
        composeRule.onNodeWithText("Native → MonoIcon").assertIsSelected().performClick()
        composeRule.runOnIdle {
            assertEquals(IconSourceStyle.NATIVE_GENERATED,
                ConfigManager.getAppIconOverridesFromUi(first.packageName).style)
        }
    }

    @Test
    fun nativeGeneratedDefaultUsesTheActualNativeLabelAndRemovesNativeDuplicates() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.NATIVE_GENERATED)
        showDetail(app = first)
        composeRule.onNodeWithText("Current source: App native").assertIsDisplayed()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("Follow default (App native)").assertIsDisplayed()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onAllNodesWithText("Native → MonoIcon").assertCountEquals(0)
        composeRule.onNodeWithText("MonoIcon generated").assertIsDisplayed()
    }

    @Test
    fun followDefaultUsesItsOwnResolvedSourceAndRemovesThatDuplicateChoice() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.LAWNICONS)
        ConfigManager.setAppIconOverrides(first.packageName, AppIconOverrides(style = IconSourceStyle.NATIVE))
        showDetail(app = first.copy(hasLawnicons = true))
        composeRule.onNodeWithText("Current source: App native").assertIsDisplayed()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("Follow default (Lawnicons)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Lawnicons").assertCountEquals(0)
        composeRule.onNodeWithText("App native").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(IconSourceStyle.NATIVE,
                ConfigManager.getAppIconOverridesFromUi(first.packageName).style)
        }
    }

    @Test
    fun authoredSourcesHideBorderAndGeneratedStyleRestoresTheSavedControl() {
        ConfigManager.setDefaultIconSourceStyle(IconSourceStyle.LAWNICONS)
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.NATIVE, borderPercent = 72))
        showDetail(app = first.copy(hasLawnicons = true))
        val borderMode = "app-icon-border-size-${first.packageName}-mode"
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onAllNodesWithTag(borderMode).assertCountEquals(0)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onAllNodesWithText("Lawnicons").assertCountEquals(0)
        composeRule.onNodeWithText("Follow default (Lawnicons)").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(borderPercent = 72),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onAllNodesWithTag(borderMode).assertCountEquals(0)
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("MonoIcon generated").performClick()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag(borderMode).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(style = IconSourceStyle.GENERATED, borderPercent = 72),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
    }

    @Test
    fun borderAvailabilityUsesTheLoadedMaskEvenWhenPreferredNativeFallsBack() {
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.NATIVE, borderPercent = 24))
        showDetail(actualSource = { _, _, _ -> MaskStrategy.SOURCE_AOSP_ADAPTIVE })
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}-mode").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun refreshedResourcesReplaceStaleDetailCapabilitiesAndKeepSavedOverrides() {
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.NATIVE, borderPercent = 24))
        val current = AtomicReference(first.copy(hasNativeMonochrome = true, hasLawnicons = true))
        val loaded = CopyOnWriteArrayList<Pair<IconSourceStyle, Boolean>>()
        showDetail(loaded = loaded, loadResources = { _, _ -> current.get() })
        composeRule.onAllNodesWithTag("app-icon-border-size-${first.packageName}-mode").assertCountEquals(0)
        current.set(first.copy(hasNativeMonochrome = false, hasLawnicons = false))
        composeRule.runOnIdle { configListeners().toList().forEach { it(null) } }
        composeRule.waitUntil(5_000) {
            loaded.size >= 2 && composeRule.onAllNodesWithTag("icon-preview-${first.identity}")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}-mode").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onAllNodesWithText("Lawnicons").assertCountEquals(0)
        composeRule.onNodeWithText("Follow default (MonoIcon generated)").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(style = IconSourceStyle.NATIVE, borderPercent = 24),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
    }

    @Test
    fun returningToGeneratedSourceWaitsForItsNewMaskBeforeShowingBorder() {
        val generatedMask = CompletableDeferred<Int>()
        val sources = CopyOnWriteArrayList<Pair<IconSourceStyle, Boolean>>()
        var generatedLoads = 0
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.GENERATED, borderPercent = 72))
        showDetail(loaded = sources, actualSource = { _, style, _ ->
            if (style == IconSourceStyle.GENERATED) {
                if (++generatedLoads == 1) MaskStrategy.SOURCE_AOSP_LEGACY else generatedMask.await()
            } else MaskStrategy.SOURCE_NATIVE
        })
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(1)
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onAllNodesWithText("App native").assertCountEquals(0)
        composeRule.onNodeWithText("Follow default (App native)").performClick()
        composeRule.waitUntil(5_000) { sources.lastOrNull()?.first == IconSourceStyle.NATIVE }
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(borderPercent = 72),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo().performClick()
        composeRule.onNodeWithText("MonoIcon generated").performClick()
        composeRule.waitUntil(5_000) { generatedLoads == 2 }
        assertEquals(IconSourceStyle.GENERATED, sources.last().first)
        val borderMode = "app-icon-border-size-${first.packageName}-mode"
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onAllNodesWithTag(borderMode).assertCountEquals(0)
        composeRule.onNodeWithTag("icon-preview-${first.identity}").assertDoesNotExist()
        generatedMask.complete(MaskStrategy.SOURCE_AOSP_ADAPTIVE)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-${first.identity}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag(borderMode).performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(AppIconOverrides(style = IconSourceStyle.GENERATED, borderPercent = 72),
                ConfigManager.getAppIconOverridesFromUi(first.packageName))
        }
    }

    @Test
    fun failedSourceRefreshHidesBorderAndRetryRestoresTheConfirmedCapability() {
        val sources = CopyOnWriteArrayList<Pair<IconSourceStyle, Boolean>>()
        var attempts = 0
        ConfigManager.setAppIconOverrides(first.packageName,
            AppIconOverrides(style = IconSourceStyle.GENERATED, borderPercent = 24))
        showDetail(loaded = sources, actualSource = { _, _, _ ->
            if (++attempts == 2) error("Temporary mask failure")
            MaskStrategy.SOURCE_AOSP_LEGACY
        })
        composeRule.runOnUiThread { configListeners().toList().forEach { it(null) } }
        composeRule.waitUntil(5_000) { sources.size >= 2 }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onAllNodesWithTag("app-icon-border-size-${first.packageName}-mode").assertCountEquals(0)
        composeRule.onNodeWithTag("icon-preview-${first.identity}").assertDoesNotExist()
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performScrollTo()
        composeRule.onNodeWithText("Icon source is temporarily unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-style-${first.packageName}").performClick()
        composeRule.onNodeWithText("Follow default (App native)").assertIsDisplayed()
        composeRule.onNodeWithText("MonoIcon generated").performClick()
        composeRule.onNodeWithText(strings.retry).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-${first.identity}").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}-mode").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(24, ConfigManager.getAppIconOverridesFromUi(first.packageName).borderPercent) }
    }

    @Test
    fun renderFailureKeepsTheCapabilityAlreadyConfirmedByTheLoadedMask() {
        var failRender = false
        ConfigManager.setAppIconOverrides(first.packageName, AppIconOverrides(style = IconSourceStyle.GENERATED))
        showDetail(renderFailure = { failRender })
        composeRule.runOnUiThread { failRender = true; ConfigManager.setIconSizePercent(61) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(strings.retry).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("kernel-page-scroll").performScrollToIndex(4)
        composeRule.onNodeWithTag("app-icon-border-size-${first.packageName}-mode").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun filtersCombineWithSearchAndCanBeCleared() {
        ConfigManager.setAppIconOverrides(first.packageName, AppIconOverrides(style = IconSourceStyle.NATIVE))
        showApps()
        awaitLoaded()
        composeRule.onNodeWithTag("app-icon-filters").performClick()
        composeRule.onNodeWithTag("app-icon-filter-style").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom").performClick()
        composeRule.onNodeWithTag("app-icon-filter-native").performScrollTo().performClick()
        composeRule.onNodeWithText("Available").performClick()
        composeRule.onNodeWithTag("app-icon-filters").performScrollTo().performClick()
        openSearch()
        composeRule.onNodeWithTag("app-icon-search").performTextInput("beta")
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-filters").performScrollTo().performClick()
        composeRule.onNodeWithTag("app-icon-clear-filters").performScrollTo().performClick()
        composeRule.onNodeWithTag("app-icon-filters").performScrollTo().performClick()
        composeRule.onNodeWithTag("app-icon-card-${second.packageName}").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("app-icon-card-${first.packageName}").assertCountEquals(0)
    }

    @Test
    fun searchClearAndCancelKeepTheSelectedFilters() {
        ConfigManager.setAppIconOverrides(first.packageName, AppIconOverrides(style = IconSourceStyle.NATIVE))
        showApps()
        awaitLoaded()
        composeRule.onNodeWithTag("app-icon-filters").performClick()
        composeRule.onNodeWithTag("app-icon-filter-style").performScrollTo().performClick()
        composeRule.onNodeWithText("Custom").performClick()
        composeRule.onNodeWithTag("app-icon-filters").performScrollTo().performClick()
        openSearch()
        composeRule.onNodeWithTag("app-icon-search").performTextInput("beta")
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-search-clear").performClick()
        composeRule.onNodeWithTag("app-icon-card-${first.packageName}").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("app-icon-card-${second.packageName}").assertCountEquals(0)
        composeRule.onNodeWithTag("app-icon-search").performTextInput("beta")
        composeRule.onNodeWithTag("app-icon-search-cancel").performClick()
        composeRule.onAllNodesWithTag("app-icon-search").assertCountEquals(0)
        composeRule.onNodeWithTag("app-icon-search-open").assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-card-${first.packageName}").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithTag("app-icon-card-${second.packageName}").assertCountEquals(0)
    }

    @Test
    fun loadingThenEmptyDoesNotInventAppRows() {
        val result = CompletableDeferred<List<LawniconsApp>>()
        showApps(load = { result.await() })
        composeRule.onNodeWithText("Loading apps…").assertIsDisplayed()
        result.complete(emptyList())
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No apps available").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("No apps available").assertIsDisplayed()
    }

    @Test
    fun failedLoadCanBeRetried() {
        var attempts = 0
        showApps(load = {
            if (++attempts == 1) error("Temporary query failure")
            emptyList()
        })
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Couldn't load apps").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(strings.retry).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No apps available").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun longListsScrollWithinCompactLandscapeHeight() {
        val apps = (0 until 50).map {
            LawniconsApp("com.example.long.package.application$it", "An application with a very long name $it", "app-$it")
        }
        showApps(load = { apps }, compact = true)
        awaitLoaded()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(apps.size)
        composeRule.onNodeWithText(apps.last().packageName).assertIsDisplayed()
        composeRule.onNodeWithTag("app-icon-edit-${apps.last().packageName}").assertIsDisplayed()
    }

    private fun configField(name: String) = ConfigManager::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun uiField(name: String) = UiPreferences::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    private fun configListeners() = configField("configListeners").get(null) as MutableSet<(String?) -> Unit>
    @Suppress("UNCHECKED_CAST")
    private fun serviceState() = configField("mutableServiceState").get(null) as MutableStateFlow<Any?>
    @Suppress("UNCHECKED_CAST")
    private fun uiState() = uiField("mutableState").get(null) as MutableStateFlow<UiSettings>
}
