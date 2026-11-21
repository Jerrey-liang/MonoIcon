package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.Variant

/** Classic Material 3 palettes from Google's 2021 specification. */
object Material3ColorEngine {
    fun iconColorsForSource(
        sourceColor: Int,
        dark: Boolean,
        variant: Variant = Variant.TONAL_SPOT,
    ): IconThemeColors = MaterialIconColorEngine.iconColorsForSource(
        sourceColor, dark, variant, IconColorEngineId.MATERIAL_3,
    )
}
