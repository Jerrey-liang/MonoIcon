package com.jerrey.monoicon

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.MonoIconApp
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose smoke test for the settings UI (Phase 14).
 *
 * Guards the runtime compatibility of the UI stack that the Kotlin 2.4 /
 * Compose 1.12 upgrade brought in:
 *  - MiuiX 0.9.3 (miuix-ui + miuix-icons) against Compose 1.12,
 *  - Haze 2's typed Glass effect on the bottom navigation bar.
 *
 * A link error in either library would surface here (or as a crash) instead of
 * only on a device walk-through.
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
}
