package com.jerrey.monoicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

/**
 * MonoIcon settings theme — Miuix (MIUI/HyperOS design language) with Monet
 * dynamic colours.
 *
 * [platformDynamicColors] derives a full Miuix [top.yukonga.miuix.kmp.theme.Colors]
 * scheme from the system wallpaper palette (Android 12+/API 31+, which is our
 * `minSdk`), so the settings UI follows the device theme exactly like HyperOS
 * system apps do. No static fallback branch is needed.
 */
@Composable
fun MonoIconTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MiuixTheme(
        colors = platformDynamicColors(dark = darkTheme),
        content = content,
    )
}
