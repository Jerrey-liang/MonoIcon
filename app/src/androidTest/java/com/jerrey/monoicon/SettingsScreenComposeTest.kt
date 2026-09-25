package com.jerrey.monoicon

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.MonoIconApp
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import com.jerrey.monoicon.ui.theme.paletteStyleForVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * Settings smoke tests and liquid-glass navigation regression tests.
 *
 * Exercises the real settings pages and their Miuix 0.9.4 backdrop graph while
 * touching, dragging, cancelling and using keyboard navigation in light/dark
 * themes and both layout directions.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsScreenRendersTabsAndOverview() {
        val strings = currentStrings()

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
        val strings = currentStrings()

        composeRule.setContent { MonoIconApp() }
        composeRule.waitForIdle()

        tab(strings.moduleSettings).performClick()
        composeRule.waitForIdle()
        // Unique to the module-settings page.
        composeRule.onNodeWithText(strings.language).assertIsDisplayed()

        tab(strings.overview).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("MonoIcon").assertIsDisplayed()
    }

    @Test
    fun liquidBarLightLtrMaintainsNavigationAcrossTouchAndKeyboard() {
        exerciseLiquidNavigation(darkTheme = false, direction = LayoutDirection.Ltr)
    }

    @Test
    fun liquidBarDarkLtrMaintainsNavigationAcrossTouchAndKeyboard() {
        exerciseLiquidNavigation(darkTheme = true, direction = LayoutDirection.Ltr)
    }

    @Test
    fun liquidBarLightRtlMaintainsNavigationAcrossTouchAndKeyboard() {
        exerciseLiquidNavigation(darkTheme = false, direction = LayoutDirection.Rtl)
    }

    @Test
    fun liquidBarDarkRtlMaintainsNavigationAcrossTouchAndKeyboard() {
        exerciseLiquidNavigation(darkTheme = true, direction = LayoutDirection.Rtl)
    }

    /**
     * Uses the real app, including SettingsScreen's page recorder and all three
     * bar layers. Configuration overrides are local to this composition; no
     * device settings or module preferences are changed.
     */
    private fun exerciseLiquidNavigation(darkTheme: Boolean, direction: LayoutDirection) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = currentStrings()
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalLayoutDirection provides direction,
            ) {
                MonoIconApp()
            }
        }
        assertNavigation(strings, selectedIndex = 0)
        val overviewX = tab(strings.overview).fetchSemanticsNode().boundsInRoot.center.x
        val moduleX = tab(strings.moduleSettings).fetchSemanticsNode().boundsInRoot.center.x
        assertTrue(
            "Tab placement must follow $direction",
            if (direction == LayoutDirection.Ltr) overviewX < moduleX else overviewX > moduleX,
        )

        // A physical tap must reach the live tabs through the glass layers.
        tab(strings.iconStyle).performTouchInput { click() }
        assertNavigation(strings, selectedIndex = 1)

        // Draw several frames while pressed and while dragging. Keeping down,
        // move and up in separate calls exercises the active glass/indicator
        // path, rather than injecting an entire gesture before a frame draws.
        longPressDrag(strings.iconStyle, strings.moduleSettings, cancelGesture = false) {
            assertNavigation(strings, selectedIndex = 1)
        }
        assertNavigation(strings, selectedIndex = 2)

        // ACTION_CANCEL must keep both the selected tab and the business page.
        longPressDrag(strings.moduleSettings, strings.overview, cancelGesture = true) {
            assertNavigation(strings, selectedIndex = 2)
        }
        assertNavigation(strings, selectedIndex = 2)

        // A fresh gesture after cancellation must still work.
        tab(strings.overview).performTouchInput { click() }
        assertNavigation(strings, selectedIndex = 0)

        // Focus + actual key events exercise the keyboard path independently
        // from the semantics onClick action used in bottomBarSwitchesPages.
        activateWithKey(strings.iconStyle, Key.Enter)
        assertNavigation(strings, selectedIndex = 1)
        activateWithKey(strings.moduleSettings, Key.Spacebar)
        assertNavigation(strings, selectedIndex = 2)
        activateWithKey(strings.overview, Key.Enter)
        assertNavigation(strings, selectedIndex = 0)
    }

    private fun longPressDrag(
        fromLabel: String,
        toLabel: String,
        cancelGesture: Boolean,
        assertWhileHeld: () -> Unit,
    ) {
        val root = composeRule.onRoot()
        val rootOrigin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val from = tab(fromLabel).fetchSemanticsNode().boundsInRoot.center - rootOrigin
        val to = tab(toLabel).fetchSemanticsNode().boundsInRoot.center - rootOrigin

        root.performTouchInput { down(from) }
        try {
            composeRule.mainClock.advanceTimeBy(650)
            composeRule.waitForIdle()
            assertWhileHeld()
            for (step in 1..6) {
                val position: Offset = from + (to - from) * (step / 6f)
                root.performTouchInput { moveTo(position, delayMillis = 32) }
                composeRule.mainClock.advanceTimeBy(32)
                composeRule.waitForIdle()
            }
            assertWhileHeld()
        } finally {
            root.performTouchInput {
                if (cancelGesture) cancel() else up()
            }
        }
        composeRule.waitForIdle()
    }

    private fun activateWithKey(label: String, key: Key) {
        tab(label).performSemanticsAction(SemanticsActions.RequestFocus)
        tab(label).assertIsFocused().performKeyInput { pressKey(key) }
    }

    private fun assertNavigation(strings: AppStrings, selectedIndex: Int) {
        composeRule.waitForIdle()
        // The recorded tab copy must not expose a second set to accessibility.
        composeRule.onAllNodes(isTab).assertCountEquals(3)
        val labels = listOf(strings.overview, strings.iconStyle, strings.moduleSettings)
        labels.forEachIndexed { index, label ->
            val node = tab(label).assertIsDisplayed().assertHasClickAction()
            if (index == selectedIndex) node.assertIsSelected() else node.assertIsNotSelected()
        }
        // Check page content, not just a duplicate title or the nav's own state.
        listOf("MonoIcon", strings.circleIcons, strings.masterSwitch).forEachIndexed { index, marker ->
            val page = composeRule.onNodeWithText(marker)
            if (index == selectedIndex) page.assertIsDisplayed() else page.assertDoesNotExist()
        }
    }

    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun tab(label: String) = composeRule.onNode(isTab and hasText(label))

    private fun currentStrings(): AppStrings {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return stringsFor(
            AppLanguage.fromId(ConfigManager.getLanguageFromUi()),
            systemPrefersChinese(context),
        )
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
