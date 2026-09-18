package com.jerrey.monoicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * MonoIcon settings theme — Miuix (MIUI/HyperOS design language) driven by the
 * same Material Dynamic Color seed the icon pipeline uses.
 *
 * The theme is generated through [ThemeController] rather than the bare
 * `platformDynamicColors(dark)` helper, because only the controller can carry a
 * `keyColor`, a [ThemeColorSpec] and a [ThemePaletteStyle] — that helper takes
 * `dark` alone and always reads the platform palette.
 *
 * ## Spec 2025 scope (Miuix 0.9.3)
 * [ThemeColorSpec.Spec2025] is requested here, but Miuix only honours it for the
 * palette styles whose underlying scheme supports the 2025 spec —
 * `TonalSpot`, `Neutral`, `Vibrant`, `Expressive`. For `Rainbow`,
 * `FruitSalad`, `Monochrome`, `Fidelity` and `Content` Miuix downgrades the
 * effective spec to `SPEC_2021` at runtime. That downgrade is expected and
 * accepted; it is not compensated for here.
 *
 * [ColorSchemeMode.MonetSystem] resolves light/dark from
 * [isSystemInDarkTheme], so a system appearance change regenerates the scheme.
 *
 * @param seedColor wallpaper seed, from `PixelMonetColorEngine.getSeedColor()`
 *   so the UI palette and the icon palette share one source.
 * @param paletteStyle palette style mapped from the selected `variant_id`.
 */
@Composable
fun MonoIconTheme(
    seedColor: Int,
    paletteStyle: ThemePaletteStyle,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val controller = remember(seedColor, paletteStyle, darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = Color(seedColor),
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = paletteStyle,
        )
    }
    MiuixTheme(
        controller = controller,
        content = content,
    )
}

/**
 * Maps a persisted `variant_id` to the Miuix palette style of the same name.
 *
 * Both sides name the same Material Color Utilities schemes, so this is a plain
 * name mapping. Unknown or empty ids fall back to `TonalSpot`, which is also
 * the default written by `ConfigManager`.
 *
 * Note: `Rainbow` / `FruitSalad` / `Monochrome` / `Fidelity` / `Content` are
 * valid styles but generate with `SPEC_2021` in Miuix 0.9.3 (see
 * [MonoIconTheme]).
 */
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
