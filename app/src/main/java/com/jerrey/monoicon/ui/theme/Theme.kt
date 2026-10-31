// Theme mapping adapted from KernelSU MiuixTheme.kt, GPL-3.0-or-later.
package com.jerrey.monoicon.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.jerrey.monoicon.ui.settings.rememberUiSettings
import top.yukonga.miuix.kmp.theme.*

@Composable
fun MonoIconTheme(
    @Suppress("UNUSED_PARAMETER") seedColor: Int,
    @Suppress("UNUSED_PARAMETER") paletteStyle: ThemePaletteStyle,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val settings = rememberUiSettings()
    val isDark = when (settings.themeMode) { 1 -> false; 2 -> true; else -> darkTheme }
    val style = runCatching { ThemePaletteStyle.valueOf(settings.colorStyle) }.getOrDefault(ThemePaletteStyle.TonalSpot)
    val supports2025 = style in setOf(ThemePaletteStyle.TonalSpot, ThemePaletteStyle.Neutral, ThemePaletteStyle.Vibrant, ThemePaletteStyle.Expressive)
    val spec = if (settings.colorSpec == "Spec2025" && supports2025) ThemeColorSpec.Spec2025 else ThemeColorSpec.Spec2021
    val keyColor = when {
        settings.keyColor != 0 -> Color(settings.keyColor)
        settings.monet -> if (isDark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
        else -> null
    }
    val mode = when {
        settings.monet && isDark -> ColorSchemeMode.MonetDark
        settings.monet -> ColorSchemeMode.MonetLight
        isDark -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.Light
    }
    val controller = remember(mode, keyColor, style, spec, isDark) {
        ThemeController(mode, keyColor = keyColor, isDark = isDark, paletteStyle = style, colorSpec = spec)
    }
    MiuixTheme(controller = controller) {
        LaunchedEffect(isDark, activity) {
            activity?.window?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDark
                    isAppearanceLightNavigationBars = !isDark
                }
                window.isNavigationBarContrastEnforced = false
            }
        }
        CompositionLocalProvider(LocalContentColor provides MiuixTheme.colorScheme.onBackground) { content() }
    }
}

internal fun paletteStyleForVariant(variantId: String?): ThemePaletteStyle =
    when (variantId?.trim()?.lowercase()) {
        "monochrome" -> ThemePaletteStyle.Monochrome
        "neutral" -> ThemePaletteStyle.Neutral
        "vibrant" -> ThemePaletteStyle.Vibrant
        "expressive" -> ThemePaletteStyle.Expressive
        "fidelity" -> ThemePaletteStyle.Fidelity
        "content" -> ThemePaletteStyle.Content
        "rainbow" -> ThemePaletteStyle.Rainbow
        "fruit_salad" -> ThemePaletteStyle.FruitSalad
        else -> ThemePaletteStyle.TonalSpot
    }
