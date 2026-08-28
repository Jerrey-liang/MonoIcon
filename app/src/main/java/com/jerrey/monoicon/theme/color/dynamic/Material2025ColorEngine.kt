package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpec
import com.jerrey.monoicon.material2025.dynamiccolor.DynamicScheme
import com.jerrey.monoicon.material2025.dynamiccolor.Variant
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.material2025.quantize.QuantizerCelebi
import com.jerrey.monoicon.material2025.scheme.SchemeContent
import com.jerrey.monoicon.material2025.scheme.SchemeExpressive
import com.jerrey.monoicon.material2025.scheme.SchemeFidelity
import com.jerrey.monoicon.material2025.scheme.SchemeFruitSalad
import com.jerrey.monoicon.material2025.scheme.SchemeMonochrome
import com.jerrey.monoicon.material2025.scheme.SchemeNeutral
import com.jerrey.monoicon.material2025.scheme.SchemeRainbow
import com.jerrey.monoicon.material2025.scheme.SchemeTonalSpot
import com.jerrey.monoicon.material2025.scheme.SchemeVibrant
import com.jerrey.monoicon.material2025.score.Score

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

    private const val MAX_COLORS = 128
    private const val FALLBACK_SOURCE = 0xFF4285F4.toInt()

    fun fromArgbPixels(pixels: IntArray): Material2025IconColors {
        val opaque = pixels.filter { (it ushr 24) == 0xFF }.toIntArray()
        val input = if (opaque.isNotEmpty()) opaque else intArrayOf(FALLBACK_SOURCE)
        val quantized = QuantizerCelebi.quantize(input, MAX_COLORS)
        val candidates = Score.score(quantized, 4, FALLBACK_SOURCE, true)
        val source = candidates.firstOrNull() ?: FALLBACK_SOURCE
        return fromSource(source, candidates)
    }

    fun fromSource(
        sourceColor: Int,
        candidates: List<Int> = listOf(sourceColor),
        variant: Variant = Variant.TONAL_SPOT,
    ): Material2025IconColors {
        val sourceHct = Hct.fromInt(sourceColor)
        fun scheme(dark: Boolean) = schemeFor(variant, sourceHct, dark)
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

    fun iconColorsForSource(
        sourceColor: Int,
        dark: Boolean,
        variant: Variant = Variant.TONAL_SPOT,
    ): IconThemeColors {
        val colors = fromSource(sourceColor, variant = variant)
        return if (dark) {
            IconThemeColors(colors.primaryDark, colors.primaryContainerDark, fallbackPalette(sourceColor))
        } else {
            IconThemeColors(colors.primaryLight, colors.primaryContainerLight, fallbackPalette(sourceColor))
        }
    }

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

    /**
     * Builds the [DynamicScheme] for [variant] with MonoIcon's fixed
     * spec 2025 / phone platform / zero contrast. All nine variants share
     * the same constructor shape, so the mapping is a plain `when`.
     */
    private fun schemeFor(variant: Variant, sourceHct: Hct, dark: Boolean): DynamicScheme {
        val spec = ColorSpec.SpecVersion.SPEC_2025
        val platform = DynamicScheme.Platform.PHONE
        return when (variant) {
            Variant.MONOCHROME -> SchemeMonochrome(sourceHct, dark, 0.0, spec, platform)
            Variant.NEUTRAL -> SchemeNeutral(sourceHct, dark, 0.0, spec, platform)
            Variant.TONAL_SPOT -> SchemeTonalSpot(sourceHct, dark, 0.0, spec, platform)
            Variant.VIBRANT -> SchemeVibrant(sourceHct, dark, 0.0, spec, platform)
            Variant.EXPRESSIVE -> SchemeExpressive(sourceHct, dark, 0.0, spec, platform)
            Variant.FIDELITY -> SchemeFidelity(sourceHct, dark, 0.0, spec, platform)
            Variant.CONTENT -> SchemeContent(sourceHct, dark, 0.0, spec, platform)
            Variant.RAINBOW -> SchemeRainbow(sourceHct, dark, 0.0, spec, platform)
            Variant.FRUIT_SALAD -> SchemeFruitSalad(sourceHct, dark, 0.0, spec, platform)
        }
    }

    private fun fallbackPalette(seed: Int): MonetPalette = MonetPalette(
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
        TonePalette.generate(seed),
    )
}
