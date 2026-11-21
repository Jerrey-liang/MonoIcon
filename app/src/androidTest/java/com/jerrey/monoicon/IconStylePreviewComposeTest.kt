package com.jerrey.monoicon

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.wallpaper.WallpaperProvenance
import com.jerrey.monoicon.theme.wallpaper.WallpaperSnapshot
import com.jerrey.monoicon.theme.wallpaper.WallpaperStatus
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.screens.IconStyleExample
import com.jerrey.monoicon.ui.screens.IconStylePreview
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.theme.MonoIconTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@RunWith(AndroidJUnit4::class)
class IconStylePreviewComposeTest {
    @get:Rule val composeRule = createComposeRule()
    private val strings = stringsFor(AppLanguage.ENGLISH, false)
    private val wallpaper = WallpaperSnapshot(
        Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888), 0xFF4285F4.toInt(),
        WallpaperProvenance.WALLPAPER_FILE, WallpaperStatus.READY, 1L, "Test wallpaper",
    )

    @Test
    fun borderOnlyFlagClearsReloadsAndFiltersSourcesWithoutReloadingForTheSlider() {
        val generatedBorderOnly = mutableStateOf(false)
        val border = mutableFloatStateOf(1f)
        val restrictedLoad = CompletableDeferred<Unit>()
        val loads = CopyOnWriteArrayList<Boolean>()
        val renders = CopyOnWriteArrayList<Pair<List<String>, Float>>()
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val mixed = listOf(
            IconStyleExample("generated-app", "Generated", mask, false,
                source = MaskStrategy.SOURCE_AOSP_LEGACY),
            IconStyleExample("native-app", "Native", mask, false,
                source = MaskStrategy.SOURCE_NATIVE),
            IconStyleExample("lawnicons-app", "Lawnicons", mask, true,
                source = MaskStrategy.SOURCE_LAWNICONS),
            IconStyleExample("framework:42", "Framework", mask, false,
                source = MaskStrategy.SOURCE_AOSP_LEGACY),
            IconStyleExample("sample-app", "Sample", mask, false, bundledSample = true,
                source = MaskStrategy.SOURCE_AOSP_LEGACY),
        )
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, 0,
                        generatedIconBorderRatio = border.floatValue,
                        wallpaperSnapshot = wallpaper,
                        generatedBorderOnly = generatedBorderOnly.value,
                        loadExamples = { _, _ ->
                            val restricted = generatedBorderOnly.value
                            loads += restricted
                            if (restricted) restrictedLoad.await()
                            mixed
                        },
                        renderExamples = { apps, _, _, _, borderRatio ->
                            renders += apps.map { it.identity } to borderRatio
                            apps.map { it.mask }
                        },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-native-app").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.runOnUiThread { generatedBorderOnly.value = true }
        composeRule.waitUntil(5_000) { loads.contains(true) }
        composeRule.onNodeWithTag("icon-preview-native-app").assertDoesNotExist()
        composeRule.onNodeWithText(strings.iconPreviewLoading).assertIsDisplayed()

        restrictedLoad.complete(Unit)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-generated-app").fetchSemanticsNodes().isNotEmpty()
        }
        for (identity in listOf("native-app", "lawnicons-app", "framework:42", "sample-app")) {
            composeRule.onNodeWithTag("icon-preview-$identity").assertDoesNotExist()
        }
        composeRule.runOnIdle {
            assertEquals(listOf("generated-app"), renders.last().first)
        }

        composeRule.runOnUiThread { border.floatValue = 0.42f }
        composeRule.waitUntil(5_000) {
            renders.any { it.first == listOf("generated-app") && it.second == 0.42f }
        }
        composeRule.runOnIdle {
            assertEquals("The flag reloads masks, while its slider only rerenders them",
                listOf(false, true), loads.toList())
        }
    }

    @Test
    fun liveGeometryAndBorderRenderAgainWithoutLoadingSourcesOrWritingPreferences() {
        val persistedRoundness = ConfigManager.getIconRoundnessPercentFromUi()
        val persistedSize = ConfigManager.getIconSizePercentFromUi()
        val persistedBorder = ConfigManager.getGeneratedIconBorderRatioFromUi()
        val persistedArtworkRoundness = ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()
        val roundness = mutableIntStateOf(0)
        val size = mutableIntStateOf(0)
        val border = mutableFloatStateOf(1f)
        val artworkRadius = mutableFloatStateOf(0f)
        val variant = mutableStateOf("tonal_spot")
        val frames = CopyOnWriteArrayList<Triple<Int, Int, Float>>()
        val palettes = CopyOnWriteArrayList<Pair<Int, Int>>()
        var loads = 0
        val examples = listOf(IconStyleExample("test-app", "Test app", Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), false))
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        roundness.intValue, size.intValue, "material_2025", variant.value, false, 0,
                        generatedIconBorderRatio = border.floatValue,
                        generatedArtworkRadiusFraction = artworkRadius.floatValue,
                        wallpaperSnapshot = wallpaper,
                        loadExamples = { _, _ -> loads++; examples },
                        renderExamples = { apps, colors, round, scale, borderRatio ->
                            frames += Triple(round, scale, borderRatio)
                            palettes += colors.foreground to colors.background
                            apps.map { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
                        },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) { frames.contains(Triple(0, 0, 1f)) }
        composeRule.onNodeWithTag("icon-preview-test-app").assertIsDisplayed()
        composeRule.onNodeWithTag("icon-preview-wallpaper").assertIsDisplayed()
        composeRule.runOnUiThread { roundness.intValue = 35; size.intValue = 80 }
        composeRule.waitUntil(5_000) { frames.contains(Triple(35, 80, 1f)) }
        composeRule.runOnUiThread { border.floatValue = 0.24f }
        composeRule.waitUntil(5_000) { frames.contains(Triple(35, 80, 0.24f)) }
        composeRule.runOnUiThread { border.floatValue = 0f }
        composeRule.waitUntil(5_000) { frames.contains(Triple(35, 80, 0f)) }
        val framesBeforeArtworkUpdate = frames.size
        composeRule.runOnUiThread { artworkRadius.floatValue = 0.5f }
        composeRule.waitUntil(5_000) { frames.size > framesBeforeArtworkUpdate }
        composeRule.runOnUiThread { variant.value = "monochrome" }
        composeRule.waitUntil(5_000) { palettes.distinct().size > 1 }
        composeRule.runOnIdle {
            assertEquals("Only source selection should reload masks", 1, loads)
            assertEquals(persistedRoundness, ConfigManager.getIconRoundnessPercentFromUi())
            assertEquals(persistedSize, ConfigManager.getIconSizePercentFromUi())
            assertEquals(persistedBorder, ConfigManager.getGeneratedIconBorderRatioFromUi(), 0f)
            assertEquals(persistedArtworkRoundness, ConfigManager.getGeneratedArtworkRoundnessPercentFromUi())
        }
    }

    @Test
    fun lawniconsToggleReloadsSourcesAndIdentifiesBundledExampleHonestly() {
        val enabled = mutableStateOf(false)
        val loads = CopyOnWriteArrayList<Boolean>()
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", enabled.value, 0,
                        wallpaperSnapshot = wallpaper,
                        loadExamples = { _, pack ->
                            loads += pack
                            listOf(IconStyleExample("sample", null, mask, pack, bundledSample = pack))
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { mask } },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) { loads.contains(false) }
        composeRule.runOnUiThread { enabled.value = true }
        composeRule.waitUntil(5_000) { loads.contains(true) }
        composeRule.onNodeWithContentDescription(strings.iconPreviewLawniconsSample + ", " + strings.lawniconsPreview)
            .assertIsDisplayed()
    }

    @Test
    fun switchingBetweenNativeStrategiesAndGeneratedReloadsSourcesWithoutAToggleChange() {
        val sourceStyle = mutableStateOf(IconSourceStyle.NATIVE)
        val loadedStyles = CopyOnWriteArrayList<IconSourceStyle>()
        val persistedStyle = ConfigManager.getDefaultIconSourceStyleFromUi()
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, 0,
                        wallpaperSnapshot = wallpaper,
                        sourceStyle = sourceStyle.value,
                        loadExamples = { _, _ ->
                            loadedStyles += sourceStyle.value
                            listOf(IconStyleExample("style-example", "Example", mask, false))
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { mask } },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) { loadedStyles.contains(IconSourceStyle.NATIVE) }
        composeRule.runOnUiThread { sourceStyle.value = IconSourceStyle.NATIVE_GENERATED }
        composeRule.waitUntil(5_000) { loadedStyles.contains(IconSourceStyle.NATIVE_GENERATED) }
        composeRule.runOnUiThread { sourceStyle.value = IconSourceStyle.GENERATED }
        composeRule.waitUntil(5_000) { loadedStyles.contains(IconSourceStyle.GENERATED) }
        composeRule.runOnIdle {
            assertEquals(persistedStyle, ConfigManager.getDefaultIconSourceStyleFromUi())
        }
    }

    @Test
    fun loadingShowsNoInventedIconAndUnavailableWallpaperIsExplained() {
        val result = CompletableDeferred<List<IconStyleExample>>()
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, 0,
                        wallpaperSnapshot = wallpaper.copy(status = WallpaperStatus.UNAVAILABLE,
                            provenance = WallpaperProvenance.STABLE_FALLBACK),
                        loadExamples = { _, _ -> result.await() },
                        renderExamples = { apps, _, _, _, _ -> apps.map { it.mask } },
                    )
                }
            }
        }
        composeRule.onNodeWithText(strings.iconPreviewLoading).assertIsDisplayed()
        composeRule.onNodeWithTag("icon-preview-wallpaper").assertDoesNotExist()
        result.complete(listOf(IconStyleExample("app", "An app", Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), false)))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(strings.iconPreviewUnavailable).assertIsDisplayed()
    }

    @Test
    fun changingAppsClearsThePreviousIconWhileTheNextAppLoads() {
        val identity = mutableStateOf("first-app")
        val nextApp = CompletableDeferred<List<IconStyleExample>>()
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, 0,
                        wallpaperSnapshot = wallpaper,
                        previewKey = identity.value,
                        singleIcon = true,
                        loadExamples = { _, _ ->
                            if (identity.value == "first-app") {
                                listOf(IconStyleExample("first-app", "First", mask, false))
                            } else nextApp.await()
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { it.mask } },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-first-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { identity.value = "next-app" }
        composeRule.onNodeWithTag("icon-preview-first-app").assertDoesNotExist()
        composeRule.onNodeWithText(strings.iconPreviewLoading).assertIsDisplayed()
        nextApp.complete(listOf(
            IconStyleExample("next-app", "Next", mask, false),
            IconStyleExample("unrelated-app", "Unrelated", mask, false),
        ))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-next-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("icon-preview-next-app").assertIsDisplayed()
        composeRule.onNodeWithTag("icon-preview-unrelated-app").assertDoesNotExist()
    }

    @Test
    fun anEmptyReloadRemovesThePreviouslyRenderedApp() {
        val revision = mutableIntStateOf(0)
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, revision.intValue,
                        wallpaperSnapshot = wallpaper,
                        previewKey = "removed-app",
                        singleIcon = true,
                        loadExamples = { _, _ ->
                            if (revision.intValue == 0) {
                                listOf(IconStyleExample("removed-app", "Removed", mask, false))
                            } else emptyList()
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { it.mask } },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-removed-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { revision.intValue++ }
        composeRule.onNodeWithTag("icon-preview-removed-app").assertDoesNotExist()
        composeRule.onNodeWithText(strings.iconPreviewEmpty).assertIsDisplayed()
    }

    @Test
    fun failedReloadRemovesThePreviouslyRenderedSourceAndShowsTheError() {
        val revision = mutableIntStateOf(0)
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        0, 0, "material_2025", "tonal_spot", false, revision.intValue,
                        wallpaperSnapshot = wallpaper,
                        previewKey = "test-app",
                        singleIcon = true,
                        loadExamples = { _, _ ->
                            if (revision.intValue > 0) error("Temporary source failure")
                            listOf(IconStyleExample("test-app", "Test", mask, false, source = MaskStrategy.SOURCE_NATIVE))
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { it.mask } },
                    )
                }
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("icon-preview-test-app").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnUiThread { revision.intValue++ }
        composeRule.onNodeWithTag("icon-preview-test-app").assertDoesNotExist()
        composeRule.onNodeWithText(strings.iconPreviewError).assertIsDisplayed()
    }

    @Test
    fun previewDescribesBorderOnlyWhenItsRealSourceCanAdjustIt() {
        val style = mutableStateOf(IconSourceStyle.NATIVE)
        val mask = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    IconStylePreview(
                        12, 34, "material_2025", "tonal_spot", false, 0,
                        wallpaperSnapshot = wallpaper,
                        sourceStyle = style.value,
                        generatedIconBorderRatio = 0.56f,
                        loadExamples = { _, _ ->
                            val source = when (style.value) {
                                IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED -> MaskStrategy.SOURCE_NATIVE
                                IconSourceStyle.LAWNICONS -> MaskStrategy.SOURCE_LAWNICONS
                                IconSourceStyle.GENERATED -> MaskStrategy.SOURCE_AOSP_ADAPTIVE
                            }
                            listOf(IconStyleExample("test-app", "Test", mask,
                                source == MaskStrategy.SOURCE_LAWNICONS, source = source))
                        },
                        renderExamples = { apps, _, _, _, _ -> apps.map { it.mask } },
                    )
                }
            }
        }
        val geometry = strings.iconRoundness + ": 12%, " + strings.iconSize + ": 34%"
        for (value in IconSourceStyle.entries) {
            composeRule.runOnUiThread { style.value = value }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("icon-preview-test-app").fetchSemanticsNodes().isNotEmpty()
            }
            val expected = geometry + if (value == IconSourceStyle.GENERATED)
                ", " + strings.iconBorderSize + ": 56%, Artwork roundness: 0%" else ""
            composeRule.onNodeWithTag("icon-style-preview").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, expected))
        }
    }
}
