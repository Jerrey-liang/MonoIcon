package com.jerrey.monoicon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.screens.RuntimeHookPoint
import com.jerrey.monoicon.ui.screens.ConfigurationRuntime
import com.jerrey.monoicon.ui.screens.ConfigurationRuntimeContent
import com.jerrey.monoicon.ui.screens.LauncherEngine
import com.jerrey.monoicon.ui.screens.LauncherEnvironment
import com.jerrey.monoicon.ui.screens.RuntimeHookProcess
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.theme.MonoIconTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@RunWith(AndroidJUnit4::class)
class ConfigurationRuntimeContentComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun earlierHyperOsDoesNotShowThemeArchiveStatus() {
        setContent(ConfigurationRuntime(environment = environment(LauncherEngine.JAVA)))
        composeRule.onNodeWithTag("hyperos4-icon-compatibility").assertDoesNotExist()
    }

    @Test
    fun hyperOs4ShowsThemeStatus() {
        setContent(
            ConfigurationRuntime(
                environment = environment(LauncherEngine.NATIVE).copy(isHyperOs4 = true, themeArchiveAvailable = true),
            ),
            themeStatus = "active",
        )
        composeRule.onNodeWithText("System theme icons").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Theme icons synced").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Apply MonoIcon to the current theme").assertDoesNotExist()
        composeRule.onNodeWithText("Restore original theme icons").assertDoesNotExist()
    }

    @Test
    fun hyperOs4ReportsThemeConflictAndOffersToApplyToCurrentTheme() {
        var accepted = false
        setContent(
            ConfigurationRuntime(
                environment = environment(LauncherEngine.JAVA).copy(isHyperOs4 = true, themeArchiveAvailable = true),
                enabled = true,
            ),
            themeStatus = "conflict",
            themeConflict = true,
            onAcceptTheme = { accepted = true },
        )
        composeRule.onNodeWithText("System theme icons changed. MonoIcon paused syncing.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Apply MonoIcon to the current theme").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(accepted) }
    }

    @Test
    fun originalThemeCanBeRestoredWithModuleOff() {
        var restored = false
        setContent(
            ConfigurationRuntime(
                environment = environment(LauncherEngine.NATIVE).copy(isHyperOs4 = true, themeArchiveAvailable = true),
                enabled = false,
            ),
            themeStatus = "conflict",
            themeConflict = true,
            canRecoverOriginal = true,
            onRecoverOriginal = { restored = true },
        )
        composeRule.onNodeWithText("Restore original theme icons").performScrollTo().performClick()
        composeRule.onNodeWithText("Apply MonoIcon to the current theme").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(restored) }
    }

    @Test
    fun historicalReportNeverClaimsCurrentAndKeepsPointOutcomes() {
        val runtime = ConfigurationRuntime(
            environment = environment(LauncherEngine.NATIVE),
            enabled = true,
            processes = listOf(RuntimeHookProcess(
                name = "com.android.systemui (Java)",
                pid = 4321,
                current = false,
                points = listOf(
                    RuntimeHookPoint("SystemUiDrawable", "SystemUiNotificationIcons", "getAppIconInner", "INSTALLED"),
                    RuntimeHookPoint("SystemUiBitmap", "SystemUiNotificationIcons", "getAppIconBitmap", "FAILED"),
                ),
            )),
        )
        setContent(runtime)

        composeRule.onNodeWithText("Received a current runtime report", substring = true).assertIsNotDisplayed()
        composeRule.onNodeWithText("No current reply", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Previous: Installed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Previous: Installation failed").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("SystemUiBitmap").performScrollTo().assertIsDisplayed()
    }

    private fun setContent(
        runtime: ConfigurationRuntime,
        themeStatus: String = "",
        themeConflict: Boolean = false,
        onAcceptTheme: () -> Unit = {},
        canRecoverOriginal: Boolean = false,
        onRecoverOriginal: () -> Unit = {},
    ) {
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides stringsFor(AppLanguage.ENGLISH, false)) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ConfigurationRuntimeContent(
                            runtime, loading = false, error = "", onRefresh = {},
                            themeStatus = themeStatus, themeConflict = themeConflict, onAcceptTheme = onAcceptTheme,
                            canRecoverOriginal = canRecoverOriginal, onRecoverOriginal = onRecoverOriginal,
                        )
                    }
                }
            }
        }
    }

    private fun environment(engine: LauncherEngine) = LauncherEnvironment(
        engine = engine,
        system = "OS4.test",
        launcher = "test (1)",
        sdk = 37,
        abi = "arm64-v8a",
    )
}
