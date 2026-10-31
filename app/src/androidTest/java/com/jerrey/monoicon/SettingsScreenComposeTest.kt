package com.jerrey.monoicon

import android.content.res.Configuration
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedDispatcher
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
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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

/** Navigation regression coverage for KernelSU's four-page Miuix pager. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeRendersFourTabsInRequestedOrder() {
        val strings = currentStrings()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { MonoIconApp() }
        }
        assertNavigation(strings, selectedIndex = 0)

        val centers = labels(strings).map { tab(it).fetchSemanticsNode().boundsInRoot.center.x }
        centers.zipWithNext().forEach { (left, right) ->
            assertTrue("Home, style, configuration and module settings must appear in order", left < right)
        }
    }

    @Test
    fun bottomBarOpensIndependentProfilePageAndSettings() {
        val strings = currentStrings()
        composeRule.setContent { MonoIconApp() }

        tab(strings.configurationManagement).performClick()
        assertNavigation(strings, selectedIndex = 2)
        composeRule.onNodeWithText(strings.profiles).assertIsDisplayed()
        composeRule.onNodeWithText(strings.profileImport).assertIsDisplayed()

        tab(strings.moduleSettings).performClick()
        assertNavigation(strings, selectedIndex = 3)
        composeRule.onNodeWithText(strings.masterSwitch).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(strings.profileImport).assertIsNotDisplayed()

        tab(strings.overview).performClick()
        assertNavigation(strings, selectedIndex = 0)
    }

    @Test
    fun pageTransitionsFollowTabOrderInLtr() {
        exercisePageTransitionDirections(LayoutDirection.Ltr)
    }

    @Test
    fun pageTransitionsMirrorTabOrderInRtl() {
        exercisePageTransitionDirections(LayoutDirection.Rtl)
    }

    @Test
    fun horizontalSwipesKeepTabsAndPagesInSync() {
        val strings = currentStrings()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { MonoIconApp() }
        }
        assertNavigation(strings, selectedIndex = 0)
        for (page in 0..2) {
            composeRule.onNodeWithTag("main-page-$page").performTouchInput { swipeLeft() }
            assertNavigation(strings, selectedIndex = page + 1)
        }
        for (page in 3 downTo 1) {
            composeRule.onNodeWithTag("main-page-$page").performTouchInput { swipeRight() }
            assertNavigation(strings, selectedIndex = page - 1)
        }
    }

    @Test
    fun rapidTabChangesKeepTheLastRequestedPage() {
        val strings = currentStrings()
        composeRule.setContent { MonoIconApp() }
        assertNavigation(strings, selectedIndex = 0)
        composeRule.mainClock.autoAdvance = false
        try {
            listOf(strings.iconStyle, strings.moduleSettings, strings.configurationManagement, strings.overview, strings.iconStyle)
                .forEach { label ->
                    tab(label).performClick()
                    composeRule.mainClock.advanceTimeBy(64)
                    composeRule.waitForIdle()
                }
            composeRule.mainClock.advanceTimeBy(2_000)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertNavigation(strings, selectedIndex = 1)
    }

    @Test
    fun switchingTabsPreservesEachPagesScrollPosition() {
        val strings = currentStrings()
        composeRule.setContent { MonoIconApp() }
        tab(strings.iconStyle).performClick()
        composeRule.onNodeWithText(strings.restart).performScrollTo().assertIsDisplayed()
        val originalY = composeRule.onNodeWithText(strings.restart).fetchSemanticsNode().positionInRoot.y

        tab(strings.configurationManagement).performClick()
        assertNavigation(strings, selectedIndex = 2)
        tab(strings.iconStyle).performClick()
        assertNavigation(strings, selectedIndex = 1)
        composeRule.onNodeWithText(strings.restart).assertIsDisplayed()
        assertEquals(
            "Returning to icon style must preserve its independent scroll position",
            originalY,
            composeRule.onNodeWithText(strings.restart).fetchSemanticsNode().positionInRoot.y,
            1f,
        )
    }

    @Test
    fun aboutAndLogsReturnToSettingsThroughToolbarAndSystemBack() {
        val strings = currentStrings()
        composeRule.setContent { MonoIconApp() }
        tab(strings.moduleSettings).performClick()
        assertNavigation(strings, selectedIndex = 3)

        listOf(true, false).forEach { about ->
            openSecondary(strings, about)
            assertSecondary(strings, about)
            composeRule.onAllNodes(isTab).assertCountEquals(0)
            composeRule.onNodeWithContentDescription(strings.back).performClick()
            assertNavigation(strings, selectedIndex = 3)

            openSecondary(strings, about)
            assertSecondary(strings, about)
            pressBack()
            assertNavigation(strings, selectedIndex = 3)
        }
    }

    @Test
    fun predictiveBackCanCancelThenReturnToTheSameSettingsTab() {
        val strings = currentStrings()
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { MonoIconApp() }
        }
        tab(strings.moduleSettings).performClick()
        openSecondary(strings, about = true)
        assertSecondary(strings, about = true)

        lateinit var backDispatcher: OnBackPressedDispatcher
        composeRule.runOnUiThread {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<ComponentActivity>().single()
            backDispatcher = activity.onBackPressedDispatcher
        }
        fun event(progress: Float) = BackEventCompat(0f, 0f, progress, BackEventCompat.EDGE_LEFT)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { backDispatcher.dispatchOnBackStarted(event(0f)) }
            composeRule.runOnUiThread { backDispatcher.dispatchOnBackProgressed(event(0.45f)) }
            composeRule.mainClock.advanceTimeBy(64)
            composeRule.runOnUiThread { backDispatcher.dispatchOnBackCancelled() }
            composeRule.mainClock.advanceTimeBy(2_000)
            assertSecondary(strings, about = true)
            composeRule.onAllNodes(isTab).assertCountEquals(0)

            composeRule.runOnUiThread { backDispatcher.dispatchOnBackStarted(event(0f)) }
            composeRule.runOnUiThread { backDispatcher.dispatchOnBackProgressed(event(0.45f)) }
            composeRule.mainClock.advanceTimeBy(64)
            composeRule.runOnUiThread { backDispatcher.onBackPressed() }
            composeRule.mainClock.advanceTimeBy(2_000)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertNavigation(strings, selectedIndex = 3)
    }

    private fun openSecondary(strings: AppStrings, about: Boolean) {
        if (about) {
            composeRule.onNodeWithText(strings.about).performScrollTo().performClick()
        } else {
            val sendLog = if (strings == stringsFor(AppLanguage.CHINESE, true)) "发送日志" else "Send logs"
            composeRule.onNodeWithText(sendLog).performScrollTo().performClick()
            composeRule.onNodeWithText(strings.logsTitle).performClick()
        }
    }

    private fun assertSecondary(strings: AppStrings, about: Boolean) {
        composeRule.waitForIdle()
        if (about) composeRule.onNodeWithTag("about-page").assertIsDisplayed()
        else composeRule.onNodeWithText(strings.logsExport).assertIsDisplayed()
    }

    /** Check the actual moving pages, independently of their labels and card contents. */
    private fun exercisePageTransitionDirections(direction: LayoutDirection) {
        val strings = currentStrings()
        val labels = labels(strings)
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { MonoIconApp() }
        }
        val settledX = labels.indices.map { index ->
            tab(labels[index]).performClick()
            composeRule.waitForIdle()
            pageX(index)
        }
        tab(strings.overview).performClick()
        assertNavigation(strings, selectedIndex = 0)
        composeRule.mainClock.autoAdvance = false
        try {
            listOf(0 to 1, 1 to 2, 2 to 3, 3 to 2, 2 to 1, 1 to 0).forEach { (from, to) ->
                val navigationSign = if (to > from) 1f else -1f
                val layoutSign = if (direction == LayoutDirection.Ltr) 1f else -1f
                val enteringSide = navigationSign * layoutSign
                tab(labels[to]).performClick()
                composeRule.mainClock.advanceTimeBy(64)
                composeRule.waitForIdle()
                val outgoingX = pageX(from)
                val incomingX = pageX(to)
                assertTrue("Outgoing page $from moves opposite to entry for $direction", (outgoingX - settledX[from]) * enteringSide < -1f)
                assertTrue("Incoming page $to enters from the navigation side for $direction", (incomingX - settledX[to]) * enteringSide > 1f)

                composeRule.mainClock.advanceTimeBy(48)
                composeRule.waitForIdle()
                assertTrue("Outgoing page $from continues moving for $direction", (pageX(from) - outgoingX) * enteringSide < -1f)
                assertTrue("Incoming page $to continues moving for $direction", (pageX(to) - incomingX) * enteringSide < -1f)
                composeRule.mainClock.advanceTimeBy(2_000)
                assertNavigation(strings, selectedIndex = to)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun pageX(index: Int): Float = composeRule.onNodeWithTag("main-page-$index").fetchSemanticsNode().positionInRoot.x

    @Test
    fun liquidBarLightLtrMaintainsNavigationAcrossTouchAndKeyboard() = exerciseLiquidNavigation(false, LayoutDirection.Ltr)

    @Test
    fun liquidBarDarkLtrMaintainsNavigationAcrossTouchAndKeyboard() = exerciseLiquidNavigation(true, LayoutDirection.Ltr)

    @Test
    fun liquidBarLightRtlMaintainsNavigationAcrossTouchAndKeyboard() = exerciseLiquidNavigation(false, LayoutDirection.Rtl)

    @Test
    fun liquidBarDarkRtlMaintainsNavigationAcrossTouchAndKeyboard() = exerciseLiquidNavigation(true, LayoutDirection.Rtl)

    /** Local theme/direction overrides leave the device and module preferences unchanged. */
    private fun exerciseLiquidNavigation(darkTheme: Boolean, direction: LayoutDirection) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val strings = currentStrings()
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalLayoutDirection provides direction) { MonoIconApp() }
        }
        assertNavigation(strings, selectedIndex = 0)
        val centers = labels(strings).map { tab(it).fetchSemanticsNode().boundsInRoot.center.x }
        centers.zipWithNext().forEach { (first, second) ->
            assertTrue("Tab placement follows $direction", if (direction == LayoutDirection.Ltr) first < second else first > second)
        }
        tab(strings.iconStyle).performTouchInput { click() }
        assertNavigation(strings, selectedIndex = 1)
        longPressDrag(strings.iconStyle, strings.moduleSettings, false) { assertNavigation(strings, 1) }
        assertNavigation(strings, selectedIndex = 3)
        longPressDrag(strings.moduleSettings, strings.overview, true) { assertNavigation(strings, 3) }
        assertNavigation(strings, selectedIndex = 3)
        tab(strings.configurationManagement).performTouchInput { click() }
        assertNavigation(strings, selectedIndex = 2)
        activateWithKey(strings.iconStyle, Key.Enter)
        assertNavigation(strings, selectedIndex = 1)
        activateWithKey(strings.moduleSettings, Key.Spacebar)
        assertNavigation(strings, selectedIndex = 3)
        activateWithKey(strings.overview, Key.Enter)
        assertNavigation(strings, selectedIndex = 0)
    }

    private fun longPressDrag(fromLabel: String, toLabel: String, cancelGesture: Boolean, assertWhileHeld: () -> Unit) {
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
            root.performTouchInput { if (cancelGesture) cancel() else up() }
        }
        composeRule.waitForIdle()
    }

    private fun activateWithKey(label: String, key: Key) {
        tab(label).performSemanticsAction(SemanticsActions.RequestFocus)
        tab(label).assertIsFocused().performKeyInput { pressKey(key) }
    }

    private fun assertNavigation(strings: AppStrings, selectedIndex: Int) {
        composeRule.waitForIdle()
        composeRule.onAllNodes(isTab).assertCountEquals(4)
        labels(strings).forEachIndexed { index, label ->
            val node = tab(label).assertIsDisplayed().assertHasClickAction()
            if (index == selectedIndex) node.assertIsSelected() else node.assertIsNotSelected()
            val page = composeRule.onNodeWithTag("main-page-$index")
            // The pager keeps neighbors alive; offscreen pages are not removed.
            if (index == selectedIndex) page.assertIsDisplayed() else page.assertIsNotDisplayed()
        }
    }

    private val hiddenFromAccessibility = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
        !hiddenFromAccessibility and !hasAnyAncestor(hiddenFromAccessibility)
    private fun tab(label: String) = composeRule.onNode(isTab and hasText(label))
    private fun labels(strings: AppStrings) = listOf(strings.overview, strings.iconStyle, strings.configurationManagement, strings.moduleSettings)

    private fun currentStrings(): AppStrings {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return stringsFor(AppLanguage.fromId(ConfigManager.getLanguageFromUi()), systemPrefersChinese(context))
    }

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
        assertEquals(ThemePaletteStyle.TonalSpot, paletteStyleForVariant(null))
        assertEquals(ThemePaletteStyle.TonalSpot, paletteStyleForVariant("nonsense"))
    }
}
