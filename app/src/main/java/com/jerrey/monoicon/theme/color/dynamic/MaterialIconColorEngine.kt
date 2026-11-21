package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.DynamicScheme
import com.jerrey.monoicon.material2025.dynamiccolor.Variant
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.material2025.scheme.SchemeContent
import com.jerrey.monoicon.material2025.scheme.SchemeExpressive
import com.jerrey.monoicon.material2025.scheme.SchemeFidelity
import com.jerrey.monoicon.material2025.scheme.SchemeFruitSalad
import com.jerrey.monoicon.material2025.scheme.SchemeMonochrome
import com.jerrey.monoicon.material2025.scheme.SchemeNeutral
import com.jerrey.monoicon.material2025.scheme.SchemeRainbow
import com.jerrey.monoicon.material2025.scheme.SchemeTonalSpot
import com.jerrey.monoicon.material2025.scheme.SchemeVibrant

/** All inputs that affect a generated icon pair, including its color specification. */
internal data class IconColorRequest(
    val sourceColor: Int,
    val dark: Boolean,
    val variant: Variant,
    val engine: IconColorEngineId,
)

/** Shared Google Material Color Utilities adapter; no Android resources or preferences. */
object MaterialIconColorEngine {
    fun iconColorsForSource(
        sourceColor: Int,
        dark: Boolean,
        variant: Variant = Variant.TONAL_SPOT,
        engine: IconColorEngineId = IconColorEngineId.DEFAULT,
    ): IconThemeColors {
        val scheme = schemeFor(variant, Hct.fromInt(sourceColor), dark, engine)
        return IconThemeColors(
            foreground = scheme.primaryPalette.tone(if (dark) 80 else 30),
            background = if (dark) scheme.secondaryPalette.tone(20) else scheme.primaryPalette.tone(90),
            palette = MonetPalette(
                accent1 = TonePalette.fromMaterialPalette(scheme.primaryPalette),
                accent2 = TonePalette.fromMaterialPalette(scheme.secondaryPalette),
                accent3 = TonePalette.fromMaterialPalette(scheme.tertiaryPalette),
                neutral1 = TonePalette.fromMaterialPalette(scheme.neutralPalette),
                neutral2 = TonePalette.fromMaterialPalette(scheme.neutralVariantPalette),
            ),
        )
    }

    internal fun schemeFor(
        variant: Variant,
        sourceHct: Hct,
        dark: Boolean,
        engine: IconColorEngineId,
    ): DynamicScheme {
        val spec = engine.specVersion
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
}
