package com.jerrey.monoicon.theme.color.dynamic

/**
 * Material You tonal palette (Phase 6.1).
 *
 * Represents the system's wallpaper-seeded color scheme as tonal
 * arrays. Each accent/neutral group contains 13 tones (0–100 in
 * steps of 10, plus 50). Currently used by [TonalMapper] for
 * light/dark-adaptive tone selection; future themes may compose
 * multi-accent icons.
 *
 * All colors are ARGB ints.
 */
data class MaterialYouPalette(
    /** Primary accent tones (tone 0, 10, ..., 100, 50). */
    val accent1: List<Int>,

    /** Secondary accent tones. */
    val accent2: List<Int>,

    /** Tertiary accent tones. */
    val accent3: List<Int>,

    /** Neutral surface tones. */
    val neutral1: List<Int>,

    /** Neutral variant tones. */
    val neutral2: List<Int>,
)
