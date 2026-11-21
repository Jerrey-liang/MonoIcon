package com.jerrey.monoicon.config

/** Preferred source; missing author resources fall back to the next usable source. */
enum class IconSourceStyle(val id: String) {
    NATIVE("native"), NATIVE_GENERATED("native_generated"), LAWNICONS("lawnicons"), GENERATED("generated");

    val permitsLawniconsFallback: Boolean
        get() = this == NATIVE || this == LAWNICONS

    companion object {
        fun fromId(id: String?): IconSourceStyle? = entries.firstOrNull { it.id == id }
    }
}

/** Null means inherit that individual setting from the global defaults. */
data class AppIconOverrides(
    val style: IconSourceStyle? = null,
    val roundnessPercent: Int? = null,
    val sizePercent: Int? = null,
    val borderPercent: Int? = null,
    val generatedArtworkRoundnessPercent: Int? = null,
    val generatedArtworkFollowIconRoundness: Boolean? = null,
) {
    val hasOverrides: Boolean get() = style != null || roundnessPercent != null || sizePercent != null ||
        borderPercent != null || generatedArtworkRoundnessPercent != null ||
        generatedArtworkFollowIconRoundness != null

    fun normalized(): AppIconOverrides = copy(
        roundnessPercent = roundnessPercent?.coerceIn(0, 100),
        sizePercent = sizePercent?.coerceIn(0, 100),
        borderPercent = borderPercent?.coerceIn(0, 100),
        generatedArtworkRoundnessPercent = generatedArtworkRoundnessPercent?.coerceIn(0, 100),
    )

    fun resolve(defaults: EffectiveIconStyle): EffectiveIconStyle {
        val value = normalized()
        return EffectiveIconStyle(value.style ?: defaults.style,
            value.roundnessPercent ?: defaults.roundnessPercent,
            value.sizePercent ?: defaults.sizePercent,
            value.borderPercent ?: defaults.borderPercent,
            value.generatedArtworkRoundnessPercent ?: defaults.generatedArtworkRoundnessPercent,
            value.generatedArtworkFollowIconRoundness ?: defaults.generatedArtworkFollowIconRoundness)
    }
}

data class EffectiveIconStyle(
    val style: IconSourceStyle,
    val roundnessPercent: Int,
    val sizePercent: Int,
    val borderPercent: Int,
    val generatedArtworkRoundnessPercent: Int = 0,
    val generatedArtworkFollowIconRoundness: Boolean = false,
) {
    val generatedBorderRatio: Float get() = borderPercent / 100f
    val generatedArtworkRadiusFraction: Float get() =
        if (generatedArtworkFollowIconRoundness) 0.25f + 0.25f * roundnessPercent.coerceIn(0, 100) / 100f
        else 0.5f * generatedArtworkRoundnessPercent.coerceIn(0, 100) / 100f
}

/** Stable text encoding also serves as the export/cache signature input. */
internal object AppIconOverridesCodec {
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    fun encode(values: Map<String, AppIconOverrides>): String = values.toSortedMap()
        .filter { (pkg, value) -> packagePattern.matches(pkg) && value.hasOverrides }
        .map { (pkg, raw) ->
            val value = raw.normalized()
            val legacy = listOf(pkg, value.style?.id.orEmpty(), value.roundnessPercent?.toString().orEmpty(),
                value.sizePercent?.toString().orEmpty(), value.borderPercent?.toString().orEmpty())
            if (value.generatedArtworkRoundnessPercent == null &&
                value.generatedArtworkFollowIconRoundness == null) legacy.joinToString("\t")
            else (legacy + listOf(value.generatedArtworkRoundnessPercent?.toString().orEmpty(),
                value.generatedArtworkFollowIconRoundness?.toString().orEmpty())).joinToString("\t")
        }.joinToString("\n")

    fun decode(encoded: String): Map<String, AppIconOverrides> = buildMap {
        encoded.lineSequence().forEach { line ->
            val parts = line.split('\t')
            if ((parts.size != 5 && parts.size != 7) || !packagePattern.matches(parts[0])) return@forEach
            val value = AppIconOverrides(IconSourceStyle.fromId(parts[1]),
                parts[2].toIntOrNull(), parts[3].toIntOrNull(), parts[4].toIntOrNull(),
                parts.getOrNull(5)?.toIntOrNull(), parts.getOrNull(6)?.toBooleanStrictOrNull()).normalized()
            if (value.hasOverrides) put(parts[0], value)
        }
    }
}
