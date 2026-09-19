package com.jerrey.monoicon.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

/**
 * MonoIcon settings theme.
 *
 * The palette comes from the framework's OWN Material 3 dynamic-colour roles
 * rather than from a re-derived scheme.
 *
 * ## Why not a seed
 * `ThemeController(keyColor = …)` regenerates a scheme with Miuix's own
 * implementation (materialkolor), which does not agree with the framework's AOSP
 * Material 3. Measured on this device, both fed the same system seed
 * (`system_accent1_500 = #806DA8`):
 *
 * | role                 | framework | Miuix `colorsFromSeed` |
 * |----------------------|-----------|------------------------|
 * | `primaryContainer`   | `#EADDFF` | `#D6C3FC`              |
 * | `secondaryContainer` | `#E9DEF8` | `#E9DEF8` (matched)    |
 *
 * Miuix's `platformDynamicColors` is a hybrid for the same reason: it reads the
 * neutral/secondary roles straight from these resources but rebuilds the primary
 * family with `colorsFromSeed`. Apps on the Android Material 3 theme overlay
 * (LSPosed Manager, for one) read the roles directly, which is why their tone
 * differs.
 *
 * ## Role mapping
 * [miuixColorsFromFrameworkRoles] mirrors Miuix's internal
 * `mapMd3RolesToMiuixColorsCommon`, which cannot be called from here because both
 * it and `MonetRoles` are `internal` in Miuix 0.9.3.
 */
@Composable
fun MonoIconTheme(
    @Suppress("UNUSED_PARAMETER") seedColor: Int,
    @Suppress("UNUSED_PARAMETER") paletteStyle: ThemePaletteStyle,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // Mirrors what LSPosed Manager does, which is what makes the two palettes match.
    //
    // LSPosed's preferences on this device read:
    //   follow_system_accent=true, palette_style=TonalSpot, color_spec=SPEC_2025
    // and it feeds those into its own `ThemeController`, i.e. it takes the system
    // seed (`R.color.system_accent1_500`, #806DA8 here) and RE-DERIVES the scheme
    // with `colorsFromSeed(seed, Spec2025, TonalSpot, dark)`.
    //
    // Deriving is not the same as reading the framework's pre-computed roles, which
    // is what this theme used to do: both are Material 3, but Miuix's implementation
    // (materialkolor) and the framework's (AOSP) disagree. Measured dark-mode
    // `primaryContainer`: deriving gives #3F384C, the framework role is #4F3D74.
    val systemSeed = remember { systemAccentSeed(context) }
    val controller = remember(systemSeed, darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
            keyColor = systemSeed?.let { Color(it) },
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = ThemePaletteStyle.TonalSpot,
        )
    }
    MiuixTheme(controller = controller, content = content)
}

/**
 * The seed the system's Monet palette is built from.
 *
 * `R.color.system_accent1_500` is the framework's own seed for accent1, and it is
 * what Miuix's `readSystemPaletteInfo` falls back to when
 * `theme_customization_overlay_packages` carries no `system_palette` field — which
 * is the case on this device.
 *
 * @return the seed ARGB, or null when the platform has no dynamic colours.
 */
private fun systemAccentSeed(context: android.content.Context): Int? {
    if (android.os.Build.VERSION.SDK_INT < 31) return null
    return runCatching {
        val id = context.resources.getIdentifier("system_accent1_500", "color", "android")
        if (id == 0) null else context.resources.getColor(id, context.theme)
    }.getOrNull()
}

/**
 * The framework's Material 3 dynamic colours, mapped into Miuix's [Colors].
 *
 * Retained for reference: reading the roles directly yields the tones the system
 * itself uses, but it is NOT what a Miuix app on this device renders, because Miuix
 * re-derives the scheme from the seed instead. See [MonoIconTheme].
 *
 * @return null when the platform has no dynamic colours, or when reading fails.
 */
@Suppress("unused")
private fun miuixColorsFromFrameworkRoles(
    context: android.content.Context,
    dark: Boolean,
): Colors? {
    if (android.os.Build.VERSION.SDK_INT < 31) return null
    return runCatching {
        val res = context.resources
        val suffix = if (dark) "_dark" else "_light"
        fun role(name: String): Color {
            val id = res.getIdentifier("system_${name}$suffix", "color", "android")
            return if (id == 0) Color.Unspecified else Color(res.getColor(id, context.theme))
        }

        val primary = role("primary")
        val onPrimary = role("on_primary")
        val primaryFixed = role("primary_fixed")
        val onPrimaryFixed = role("on_primary_fixed")
        val error = role("error")
        val onError = role("on_error")
        val errorContainer = role("error_container")
        val onErrorContainer = role("on_error_container")
        val primaryContainer = role("primary_container")
        val onPrimaryContainer = role("on_primary_container")
        val secondary = role("secondary")
        val onSecondary = role("on_secondary")
        val secondaryContainer = role("secondary_container")
        val onSecondaryContainer = role("on_secondary_container")
        val tertiaryContainer = role("tertiary_container")
        val onTertiaryContainer = role("on_tertiary_container")
        val background = role("background")
        val onBackground = role("on_background")
        val surface = role("surface")
        val onSurface = role("on_surface")
        val surfaceVariant = role("surface_variant")
        val surfaceContainer = role("surface_container")
        val surfaceContainerHigh = role("surface_container_high")
        val surfaceContainerHighest = role("surface_container_highest")
        val outline = role("outline")
        val outlineVariant = role("outline_variant")
        val onSurfaceVariant = role("on_surface_variant")

        // Derived roles, following Miuix's own mapping.
        val onSurfaceStrong = onSurface.copy(alpha = 0.8f).compositeOver(surface)
        val onSurfaceContainerHigh = onSurface.copy(alpha = 0.8f).compositeOver(surfaceContainerHigh)
        val disabledPrimary = primary.copy(alpha = 0.38f).compositeOver(surface)
        val disabledOnPrimary = onPrimary.copy(alpha = 0.38f).compositeOver(disabledPrimary)
        val disabledPrimaryButton = primary.copy(alpha = 0.38f).compositeOver(surface)
        val disabledOnPrimaryButton = onPrimary.copy(alpha = 0.6f).compositeOver(disabledPrimaryButton)
        val disabledPrimarySlider = primary.copy(alpha = 0.38f).compositeOver(surface)
        val disabledSecondary = outlineVariant.copy(alpha = 0.5f).compositeOver(surface)
        val disabledOnSecondary = onSurface.copy(alpha = 0.38f).compositeOver(disabledSecondary)
        val disabledSecondaryVariant = surfaceContainerHigh.copy(alpha = 0.6f).compositeOver(surface)
        val disabledOnSecondaryVariant = onSurface.copy(alpha = 0.38f).compositeOver(disabledSecondaryVariant)
        val windowDimming = Color.Black.copy(alpha = if (dark) 0.6f else 0.3f)
        val sliderBackground = primary.copy(alpha = 0.2f).compositeOver(surface)

        buildColorScheme(
            dark = dark,
            primary = primary,
            onPrimary = onPrimary,
            primaryVariant = primaryFixed,
            onPrimaryVariant = onPrimaryFixed,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            disabledPrimary = disabledPrimary,
            disabledOnPrimary = disabledOnPrimary,
            disabledPrimaryButton = disabledPrimaryButton,
            disabledOnPrimaryButton = disabledOnPrimaryButton,
            disabledPrimarySlider = disabledPrimarySlider,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryVariant = outlineVariant,
            onSecondaryVariant = outline,
            disabledSecondary = disabledSecondary,
            disabledOnSecondary = disabledOnSecondary,
            disabledSecondaryVariant = disabledSecondaryVariant,
            disabledOnSecondaryVariant = disabledOnSecondaryVariant,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            secondaryContainerVariant = surfaceContainerHighest,
            onSecondaryContainerVariant = onSurfaceVariant,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            tertiaryContainerVariant = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            onBackgroundVariant = primary,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceSecondary = onSurfaceStrong,
            onSurfaceVariantSummary = onSurfaceVariant,
            onSurfaceVariantActions = onSurfaceVariant,
            disabledOnSurface = onSurface,
            surfaceContainer = surfaceContainer,
            onSurfaceContainer = onSurface,
            onSurfaceContainerVariant = onSurfaceVariant,
            surfaceContainerHigh = surfaceContainerHigh,
            onSurfaceContainerHigh = onSurfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            onSurfaceContainerHighest = onSurface,
            outline = outline,
            dividerLine = outlineVariant,
            windowDimming = windowDimming,
            sliderKeyPoint = primary,
            sliderKeyPointForeground = surfaceContainerHigh,
            sliderBackground = sliderBackground,
        )
    }.getOrNull()
}

private fun buildColorScheme(
    dark: Boolean,
    primary: Color,
    onPrimary: Color,
    primaryVariant: Color,
    onPrimaryVariant: Color,
    error: Color,
    onError: Color,
    errorContainer: Color,
    onErrorContainer: Color,
    disabledPrimary: Color,
    disabledOnPrimary: Color,
    disabledPrimaryButton: Color,
    disabledOnPrimaryButton: Color,
    disabledPrimarySlider: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    onSecondary: Color,
    secondaryVariant: Color,
    onSecondaryVariant: Color,
    disabledSecondary: Color,
    disabledOnSecondary: Color,
    disabledSecondaryVariant: Color,
    disabledOnSecondaryVariant: Color,
    secondaryContainer: Color,
    onSecondaryContainer: Color,
    secondaryContainerVariant: Color,
    onSecondaryContainerVariant: Color,
    tertiaryContainer: Color,
    onTertiaryContainer: Color,
    tertiaryContainerVariant: Color,
    background: Color,
    onBackground: Color,
    onBackgroundVariant: Color,
    surface: Color,
    onSurface: Color,
    surfaceVariant: Color,
    onSurfaceSecondary: Color,
    onSurfaceVariantSummary: Color,
    onSurfaceVariantActions: Color,
    disabledOnSurface: Color,
    surfaceContainer: Color,
    onSurfaceContainer: Color,
    onSurfaceContainerVariant: Color,
    surfaceContainerHigh: Color,
    onSurfaceContainerHigh: Color,
    surfaceContainerHighest: Color,
    onSurfaceContainerHighest: Color,
    outline: Color,
    dividerLine: Color,
    windowDimming: Color,
    sliderKeyPoint: Color,
    sliderKeyPointForeground: Color,
    sliderBackground: Color,
): Colors = if (dark) {
    darkColorScheme(
        primary, onPrimary, primaryVariant, onPrimaryVariant, error, onError,
        errorContainer, onErrorContainer, disabledPrimary, disabledOnPrimary,
        disabledPrimaryButton, disabledOnPrimaryButton, disabledPrimarySlider,
        primaryContainer, onPrimaryContainer, secondary, onSecondary,
        secondaryVariant, onSecondaryVariant, disabledSecondary, disabledOnSecondary,
        disabledSecondaryVariant, disabledOnSecondaryVariant, secondaryContainer,
        onSecondaryContainer, secondaryContainerVariant, onSecondaryContainerVariant,
        tertiaryContainer, onTertiaryContainer, tertiaryContainerVariant,
        background, onBackground, onBackgroundVariant, surface, onSurface,
        surfaceVariant, onSurfaceSecondary, onSurfaceVariantSummary,
        onSurfaceVariantActions, disabledOnSurface, surfaceContainer,
        onSurfaceContainer, onSurfaceContainerVariant, surfaceContainerHigh,
        onSurfaceContainerHigh, surfaceContainerHighest, onSurfaceContainerHighest,
        outline, dividerLine, windowDimming, sliderKeyPoint, sliderKeyPointForeground,
        sliderBackground,
    )
} else {
    lightColorScheme(
        primary, onPrimary, primaryVariant, onPrimaryVariant, error, onError,
        errorContainer, onErrorContainer, disabledPrimary, disabledOnPrimary,
        disabledPrimaryButton, disabledOnPrimaryButton, disabledPrimarySlider,
        primaryContainer, onPrimaryContainer, secondary, onSecondary,
        secondaryVariant, onSecondaryVariant, disabledSecondary, disabledOnSecondary,
        disabledSecondaryVariant, disabledOnSecondaryVariant, secondaryContainer,
        onSecondaryContainer, secondaryContainerVariant, onSecondaryContainerVariant,
        tertiaryContainer, onTertiaryContainer, tertiaryContainerVariant,
        background, onBackground, onBackgroundVariant, surface, onSurface,
        surfaceVariant, onSurfaceSecondary, onSurfaceVariantSummary,
        onSurfaceVariantActions, disabledOnSurface, surfaceContainer,
        onSurfaceContainer, onSurfaceContainerVariant, surfaceContainerHigh,
        onSurfaceContainerHigh, surfaceContainerHighest, onSurfaceContainerHighest,
        outline, dividerLine, windowDimming, sliderKeyPoint, sliderKeyPointForeground,
        sliderBackground,
    )
}

/** Alpha-composite `this` over [background]; used for Miuix's derived roles. */
private fun Color.compositeOver(background: Color): Color {
    val a = alpha + background.alpha * (1f - alpha)
    if (a == 0f) return Color.Transparent
    val r = (red * alpha + background.red * background.alpha * (1f - alpha)) / a
    val g = (green * alpha + background.green * background.alpha * (1f - alpha)) / a
    val b = (blue * alpha + background.blue * background.alpha * (1f - alpha)) / a
    return Color(r, g, b, a)
}

/**
 * Maps a persisted `variant_id` to the Miuix palette style of the same name.
 *
 * Both sides name the same Material Color Utilities schemes, so this is a plain
 * name mapping. Unknown or empty ids fall back to `TonalSpot`, which is also
 * the default written by `ConfigManager`.
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
