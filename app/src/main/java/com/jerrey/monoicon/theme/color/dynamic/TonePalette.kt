package com.jerrey.monoicon.theme.color.dynamic

/**
 * A single HCT (Hue-Chroma-Tone) tonal palette — 13 shades derived
 * from one seed hue at fixed perceptual lightness steps (Phase 6.3).
 *
 * Now uses [HctPalette] for CAM16-based tone generation, matching
 * Pixel Launcher's `TonalPalette` output.
 *
 * Tones: 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100.
 */
class TonePalette private constructor(private val colors: IntArray) {

    val tones: IntArray get() = colors.copyOf()

    operator fun get(i: Int): Int = colors[i.coerceIn(0, 12)]

    companion object {
        const val COUNT = 13

        /** Generate a 13-tone palette from [seedColor] using CAM16 HCT. */
        fun generate(seedColor: Int): TonePalette = try {
            TonePalette(HctPalette.generate(seedColor))
        } catch (_: Throwable) {
            // HCT failure → fall back to equi-weight grayscale ramp
            val arr = IntArray(COUNT)
            val tones = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)
            val r = android.graphics.Color.red(seedColor)
            val g = android.graphics.Color.green(seedColor)
            val b = android.graphics.Color.blue(seedColor)
            val seedY = (0.299f * r + 0.587f * g + 0.114f * b)
            for (i in tones.indices) {
                val targetY = tones[i] * 2.55f
                arr[i] = if (targetY > seedY) {
                    val ratio = ((targetY - seedY) / (255f - seedY)).coerceIn(0f, 1f)
                    android.graphics.Color.rgb(
                        (r + (255 - r) * ratio).toInt().coerceIn(0, 255),
                        (g + (255 - g) * ratio).toInt().coerceIn(0, 255),
                        (b + (255 - b) * ratio).toInt().coerceIn(0, 255))
                } else {
                    val ratio = ((seedY - targetY) / seedY.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    android.graphics.Color.rgb(
                        (r * (1f - ratio)).toInt().coerceIn(0, 255),
                        (g * (1f - ratio)).toInt().coerceIn(0, 255),
                        (b * (1f - ratio)).toInt().coerceIn(0, 255))
                }
            }
            TonePalette(arr)
        }
    }
}
