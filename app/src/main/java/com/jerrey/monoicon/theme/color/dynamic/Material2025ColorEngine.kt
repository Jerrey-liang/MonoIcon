package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.Variant
import com.jerrey.monoicon.material2025.hct.Hct

/** Pure Google Material Color Utilities 2025 calculation used by MonoIcon. */
object Material2025ColorEngine {

    /** Stable IDs used by the settings UI and remote preferences. */
    enum class VariantId(val id: String, val label: String) {
        MONOCHROME("monochrome", "Monochrome"),
        NEUTRAL("neutral", "Neutral"),
        TONAL_SPOT("tonal_spot", "Tonal Spot"),
        VIBRANT("vibrant", "Vibrant"),
        EXPRESSIVE("expressive", "Expressive"),
        FIDELITY("fidelity", "Fidelity"),
        CONTENT("content", "Content"),
        RAINBOW("rainbow", "Rainbow"),
        FRUIT_SALAD("fruit_salad", "Fruit Salad");

        companion object {
            fun fromId(id: String?): VariantId = values().firstOrNull { it.id == id } ?: TONAL_SPOT
        }
    }

    val availableVariants: List<VariantId> = VariantId.values().toList()

    data class Material2025Source(
        val argbPixels: IntArray,
        val width: Int,
        val height: Int,
    )

    data class Material2025IconColors(
        val sourceColor: Int,
        val sourceCandidates: List<Int>,
        val sourceHue: Double,
        val sourceChroma: Double,
        val sourceTone: Double,
        val primaryLight: Int,
        val primaryContainerLight: Int,
        val primaryDark: Int,
        val primaryContainerDark: Int,
    )

    fun fromSource(
        sourceColor: Int,
        candidates: List<Int> = listOf(sourceColor),
        variant: Variant = Variant.TONAL_SPOT,
    ): Material2025IconColors {
        val sourceHct = Hct.fromInt(sourceColor)
        fun scheme(dark: Boolean) = MaterialIconColorEngine.schemeFor(
            variant, sourceHct, dark, IconColorEngineId.MATERIAL_2025,
        )
        val light = scheme(false)
        val dark = scheme(true)
        return Material2025IconColors(
            sourceColor = sourceColor,
            sourceCandidates = candidates,
            sourceHue = sourceHct.hue,
            sourceChroma = sourceHct.chroma,
            sourceTone = sourceHct.tone,
            primaryLight = light.primary,
            primaryContainerLight = light.primaryContainer,
            primaryDark = dark.primary,
            primaryContainerDark = dark.primaryContainer,
        )
    }

    /**
     * AOSP themed-icon color contract (iconloaderlib
     * `res/values-v31/colors.xml` + `values-night-v31` + SystemUI
     * `TonalPalette.getAtTone` where `tone = (1000 - shade) / 10`):
     *
     * - light: fg = `themed_icon_color` = system_accent1_700 → primary.tone(30)
     *          bg = `themed_icon_background_color` = system_accent1_100 → primary.tone(90)
     * - dark:  fg = system_accent1_200 → primary.tone(80)
     *          bg = system_accent2_800 → secondary.tone(20)
     *
     * `mAccent1`/`mAccent2` in SystemUI `ColorScheme` are the primary and
     * secondary palettes of the wallpaper seed scheme; the built-in theme
     * uses TONAL_SPOT (AOSP default style), and the 9-variant setting only
     * changes the scheme source for the same AOSP tone mapping.
     */
    fun iconColorsForSource(
        sourceColor: Int,
        dark: Boolean,
        variant: Variant = Variant.TONAL_SPOT,
    ): IconThemeColors = MaterialIconColorEngine.iconColorsForSource(
        sourceColor, dark, variant, IconColorEngineId.MATERIAL_2025,
    )

    fun variantFromId(id: String?): Variant = when (VariantId.fromId(id)) {
        VariantId.MONOCHROME -> Variant.MONOCHROME
        VariantId.NEUTRAL -> Variant.NEUTRAL
        VariantId.TONAL_SPOT -> Variant.TONAL_SPOT
        VariantId.VIBRANT -> Variant.VIBRANT
        VariantId.EXPRESSIVE -> Variant.EXPRESSIVE
        VariantId.FIDELITY -> Variant.FIDELITY
        VariantId.CONTENT -> Variant.CONTENT
        VariantId.RAINBOW -> Variant.RAINBOW
        VariantId.FRUIT_SALAD -> Variant.FRUIT_SALAD
    }

}
