package com.jerrey.monoicon

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.screens.ConfigurationRuntime
import com.jerrey.monoicon.ui.screens.ConfigurationRuntimeContent
import com.jerrey.monoicon.ui.screens.LauncherEngine
import com.jerrey.monoicon.ui.screens.LauncherEnvironment
import com.jerrey.monoicon.ui.screens.RuntimeHookPoint
import com.jerrey.monoicon.ui.screens.RuntimeHookProcess
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.theme.MonoIconTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

@RunWith(AndroidJUnit4::class)
class ConfigurationRuntimeContentComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun javaLauncherDoesNotShowProfileContent() {
        setContent(LauncherEngine.JAVA)
        composeRule.onNodeWithText(PROFILE_MARKER).assertIsNotDisplayed()
    }

    @Test
    fun unknownLauncherDoesNotShowProfileContent() {
        setContent(LauncherEngine.UNKNOWN)
        composeRule.onNodeWithText(PROFILE_MARKER).assertIsNotDisplayed()
    }

    @Test
    fun nativeLauncherShowsProfileContent() {
        setContent(LauncherEngine.NATIVE)
        composeRule.onNodeWithText(PROFILE_MARKER).assertIsDisplayed()
    }

    @Test
    fun historicalReportNeverClaimsCurrentAndKeepsPointOutcomes() {
        val runtime = ConfigurationRuntime(
            environment = environment(LauncherEngine.NATIVE),
            enabled = true,
            processes = listOf(RuntimeHookProcess(
                name = "com.miui.home · Native",
                pid = 4321,
                current = false,
                points = listOf(
                    RuntimeHookPoint("installed-point", "profile-patch", "function", status = "INSTALLED"),
                    RuntimeHookPoint("failed-point", "profile-patch", "function", status = "FAILED"),
                    RuntimeHookPoint("unknown-point", "profile-patch", "function", status = "UNKNOWN"),
                ),
            )),
        )
        setContent(runtime)

        composeRule.onNodeWithText("Received a current runtime report", substring = true).assertIsNotDisplayed()
        composeRule.onNodeWithText("No current reply", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Previous: Installed · not observed").assertIsDisplayed()
        composeRule.onNodeWithText("Previous: Installation failed").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown / unreported").assertIsDisplayed()
    }

    private fun setContent(engine: LauncherEngine) = setContent(ConfigurationRuntime(environment = environment(engine)))

    private fun setContent(runtime: ConfigurationRuntime) {
        composeRule.setContent {
            MonoIconTheme(seedColor = 0, paletteStyle = ThemePaletteStyle.TonalSpot) {
                CompositionLocalProvider(LocalStrings provides stringsFor(AppLanguage.ENGLISH, false)) {
                    ConfigurationRuntimeContent(runtime, loading = false, error = "", onRefresh = {}) {
                        Text(PROFILE_MARKER)
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

    private companion object {
        const val PROFILE_MARKER = "PROFILE-MARKER"
    }
}
