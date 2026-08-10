package com.jerrey.monoicon.theme.color.dynamic

/**
 * Full HCT-color tonal palette from a single wallpaper seed (Phase 6.3).
 *
 * Uses [HctPalette] for CAM16-based tone generation, matching Pixel
 * Launcher's `ColorScheme` output.
 *
 * Chroma ratios (from Pixel Launcher `DynamicScheme`):
 * - accent1: full chroma (primary)
 * - accent2: slightly reduced chroma (secondary)
 * - accent3: more reduced chroma (tertiary)
 * - neutral1/2: near-zero chroma (surface tones)
 */
data class MonetPalette(
    val accent1: TonePalette,
    val accent2: TonePalette,
    val accent3: TonePalette,
    val neutral1: TonePalette,
    val neutral2: TonePalette,
) {
    companion object {
        private const val CHROMA_ACCENT2 = 0.75f
        private const val CHROMA_ACCENT3 = 0.50f
        private const val CHROMA_NEUTRAL = 0.08f

        fun generate(seedColor: Int): MonetPalette {
            return MonetPalette(
                accent1 = TonePalette.generate(seedColor),
                accent2 = TonePalette.generate(adjustChroma(seedColor, CHROMA_ACCENT2)),
                accent3 = TonePalette.generate(adjustChroma(seedColor, CHROMA_ACCENT3)),
                neutral1 = TonePalette.generate(adjustChroma(seedColor, CHROMA_NEUTRAL)),
                neutral2 = TonePalette.generate(adjustChroma(seedColor, CHROMA_NEUTRAL)),
            )
        }

        /** Reduces chroma of [color] by [factor] (0=grayscale, 1=unchanged) for seed variants. */
        private fun adjustChroma(color: Int, factor: Float): Int {
            val r = android.graphics.Color.red(color)
            val g = android.graphics.Color.green(color)
            val b = android.graphics.Color.blue(color)
            val y = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
            return android.graphics.Color.rgb(
                (y + (r - y) * factor).toInt().coerceIn(0, 255),
                (y + (g - y) * factor).toInt().coerceIn(0, 255),
                (y + (b - y) * factor).toInt().coerceIn(0, 255)
            )
        }
    }
}
