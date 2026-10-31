package com.jerrey.monoicon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.jerrey.monoicon.ui.settings.AppUpdateChecker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jerrey.monoicon.ui.settings.rememberUiSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.ui.screens.SettingsScreen
import com.jerrey.monoicon.ui.theme.MonoIconTheme
import com.jerrey.monoicon.ui.theme.paletteStyleForVariant

/**
 * Root composable for the MonoIcon configuration UI.
 *
 * Keeps icon rendering preferences in ConfigManager and observes the manager's
 * separate theme, scale and update preferences, as in KernelSU.
 */
@Composable
fun MonoIconApp() {
    val settings = rememberUiSettings()
    val context = LocalContext.current
    LaunchedEffect(settings.checkUpdate) {
        if (settings.checkUpdate) AppUpdateChecker.check(context)
    }
    val systemDensity = LocalDensity.current
    var variant by remember { mutableStateOf(ConfigManager.getVariantIdFromUi()) }
    val seedColor = PixelMonetColorEngine.getSeedColor()

    MonoIconTheme(
        seedColor = seedColor,
        paletteStyle = paletteStyleForVariant(variant),
        darkTheme = isSystemInDarkTheme(),
    ) {
        CompositionLocalProvider(LocalDensity provides Density(systemDensity.density * settings.pageScale, systemDensity.fontScale)) {
            SettingsScreen(
                variant = variant,
                onVariantChange = { variant = it },
            )
        }
    }
}
