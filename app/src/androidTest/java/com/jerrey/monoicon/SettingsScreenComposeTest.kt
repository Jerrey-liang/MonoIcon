package com.jerrey.monoicon

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.MonoIconApp
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import com.jerrey.monoicon.ui.theme.paletteStyleForVariant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * Compose smoke test for the settings UI.
 *
 * Guards the runtime compatibility of the UI stack:
 *  - Miuix 0.9.3 (miuix-ui + miuix-icons + miuix-blur) against the current
 *    Compose version — the liquid-glass bottom bar is built on miuix-blur's
 *    backdrop modifiers;
 *  - the `ThemeController`-based theme wiring, where the UI palette is
 *    generated from the same seed and variant as the icon pipeline.
 *
 * A link error in either library, or a crash while building the dynamic colour
 * scheme, surfaces here instead of only on a device walk-through.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreenRendersTabsAndOverview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = stringsFor(AppLanguage.SYSTEM, systemPrefersChinese(context))

        composeRule.setContent { MonoIconApp() }
        composeRule.waitForIdle()

        // Overview page: large title + the three glass-nav tabs.
        composeRule.onNodeWithText("MonoIcon").assertIsDisplayed()
        composeRule.onNodeWithText(strings.overview).assertIsDisplayed()
        composeRule.onNodeWithText(strings.moduleSettings).assertIsDisplayed()
        composeRule.onNodeWithText(strings.iconStyle).assertIsDisplayed()
    }

    /**
     * The bottom bar drives page switching: tapping the module tab must render
     * that page's content, and tapping back must restore the overview.
     *
     * The tab label is used as the click target because the bar renders it for
     * every tab regardless of which page is showing.
     */
    @Test
    fun bottomBarSwitchesPages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = stringsFor(AppLanguage.SYSTEM, systemPrefersChinese(context))

        composeRule.setContent { MonoIconApp() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(strings.moduleSettings).performClick()
        composeRule.waitForIdle()
        // Unique to the module-settings page.
        composeRule.onNodeWithText(strings.language).assertIsDisplayed()

        composeRule.onNodeWithText(strings.overview).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MonoIcon").assertIsDisplayed()
    }

    /**
     * Every persisted `variant_id` must resolve to a Miuix palette style, and an
     * unknown id must not throw. This is the contract that keeps the UI palette
     * aligned with the icon colour variants.
     *
     * The 2025/2021 spec split is Miuix's decision (only TonalSpot, Neutral,
     * Vibrant and Expressive honour SPEC_2025), so it is not asserted here.
     */
    @Test
    fun variantIdsMapToPaletteStyles() {
        assertEquals(ThemePaletteStyle.TonalSpot, paletteStyleForVariant("tonal_spot"))
        assertEquals(ThemePaletteStyle.Neutral, paletteStyleForVariant("neutral"))
        assertEquals(ThemePaletteStyle.Vibrant, paletteStyleForVariant("vibrant"))
        assertEquals(ThemePaletteStyle.Expressive, paletteStyleForVariant("expressive"))
        assertEquals(ThemePaletteStyle.Fidelity, paletteStyleForVariant("fidelity"))
        assertEquals(ThemePaletteStyle.Content, paletteStyleForVariant("content"))
        assertEquals(ThemePaletteStyle.Rainbow, paletteStyleForVariant("rainbow"))
        assertEquals(ThemePaletteStyle.FruitSalad, paletteStyleForVariant("fruit_salad"))
        assertEquals(ThemePaletteStyle.Monochrome, paletteStyleForVariant("monochrome"))
        // Unknown / empty falls back to the default the module writes.
        assertEquals(ThemePaletteStyle.TonalSpot, paletteStyleForVariant(null))
        assertEquals(ThemePaletteStyle.TonalSpot, paletteStyleForVariant("nonsense"))
    }
}
