package com.jerrey.monoicon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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
 * Owns the one piece of state the theme needs: the selected colour variant.
 * `ConfigManager` reads it once and exposes no observable, so the value is held
 * here and passed down as a normal Compose parameter — when the user picks a
 * different variant on the icon-style page the theme regenerates, and the UI
 * and the icon pipeline stay on the same variant.
 */
@Composable
fun MonoIconApp() {
    var variant by remember { mutableStateOf(ConfigManager.getVariantIdFromUi()) }
    val seedColor = PixelMonetColorEngine.getSeedColor()

    MonoIconTheme(
        seedColor = seedColor,
        paletteStyle = paletteStyleForVariant(variant),
        darkTheme = isSystemInDarkTheme(),
    ) {
        SettingsScreen(
            variant = variant,
            onVariantChange = { variant = it },
        )
    }
}
