package com.jerrey.monoicon.ui

import androidx.compose.runtime.Composable
import com.jerrey.monoicon.ui.screens.SettingsScreen
import com.jerrey.monoicon.ui.theme.MonoIconTheme

/**
 * Root composable for the MonoIcon configuration UI.
 *
 * This is the entry point for the Compose-based settings screen
 * that allows users to configure the module's behavior.
 */
@Composable
fun MonoIconApp() {
    MonoIconTheme {
        SettingsScreen()
    }
}
