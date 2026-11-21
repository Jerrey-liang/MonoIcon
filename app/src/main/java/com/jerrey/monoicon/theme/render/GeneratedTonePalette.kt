package com.jerrey.monoicon.theme.render

/** Palette mapping for generated grayscale artwork whose alpha is source coverage. */
internal object GeneratedTonePalette {
    data class Endpoints(val dark: Int, val light: Int)

    /**
     * Order the palette by luminance, keeping the background-facing endpoint
     * 20% toward foreground. White artwork remains distinct from a light plate,
     * and dark strokes remain distinct from a dark plate. Endpoint RGB is opaque;
     * mapping always preserves the artwork's own alpha instead of palette alpha.
     */
    fun endpoints(foreground: Int, background: Int): Endpoints {
        val foregroundRgb = foreground or OPAQUE
        val nearBackground = OPAQUE or
            (nearBackgroundChannel(foreground, background, 16) shl 16) or
            (nearBackgroundChannel(foreground, background, 8) shl 8) or
            nearBackgroundChannel(foreground, background, 0)
        return if (luminance(foreground) <= luminance(background)) {
            Endpoints(foregroundRgb, nearBackground)
        } else {
            Endpoints(nearBackground, foregroundRgb)
        }
    }

    /** Android ColorMatrix's 4x5 layout; grayscale red supplies tone, alpha is identity. */
    fun colorMatrix(foreground: Int, background: Int): FloatArray {
        val colors = endpoints(foreground, background)
        fun start(shift: Int) = (colors.dark ushr shift and 255).toFloat()
        fun slope(shift: Int) = ((colors.light ushr shift and 255) - start(shift)) / 255f
        return floatArrayOf(
            slope(16), 0f, 0f, 0f, start(16),
            slope(8), 0f, 0f, 0f, start(8),
            slope(0), 0f, 0f, 0f, start(0),
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Pure pixel equivalent of [colorMatrix], also usable by offline regression drivers. */
    fun map(pixel: Int, foreground: Int, background: Int): Int {
        val coverage = pixel ushr 24
        if (coverage == 0) return 0
        val colors = endpoints(foreground, background)
        val tone = pixel ushr 16 and 255
        fun channel(shift: Int): Int = (
            (colors.dark ushr shift and 255) * (255 - tone) +
                (colors.light ushr shift and 255) * tone + 127
            ) / 255
        return (coverage shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun nearBackgroundChannel(foreground: Int, background: Int, shift: Int): Int =
        ((background ushr shift and 255) * 4 + (foreground ushr shift and 255) + 2) / 5

    private fun luminance(color: Int): Int =
        (color ushr 16 and 255) * 2126 + (color ushr 8 and 255) * 7152 + (color and 255) * 722

    private const val OPAQUE = -0x1000000
}
