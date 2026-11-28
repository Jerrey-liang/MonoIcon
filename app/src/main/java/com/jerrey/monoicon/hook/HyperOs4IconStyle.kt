package com.jerrey.monoicon.hook

import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.AppIconOverridesCodec
import com.jerrey.monoicon.config.IconSourceStyle
import java.security.MessageDigest

/** Settings included in the theme renderer's cache signature. */
internal data class HyperOs4IconStyle(
    val theme: String,
    val engine: String,
    val variant: String,
    val circle: Boolean,
    val lawnicons: Boolean,
    val disabledPackages: Set<String>,
    val roundnessPercent: Int = if (circle) 100 else 0,
    val sizePercent: Int = 0,
    val generatedBorderPercent: Int = 100,
    val sourceStyle: IconSourceStyle = if (lawnicons) IconSourceStyle.LAWNICONS else IconSourceStyle.NATIVE_GENERATED,
    val appOverrides: Map<String, AppIconOverrides> = emptyMap(),
    val lawniconsFallback: Boolean = sourceStyle.permitsLawniconsFallback,
    val generatedArtworkRoundnessPercent: Int = 0,
    val generatedArtworkFollowIconRoundness: Boolean = false,
) {
    fun signature(): String {
        // Distinguish restored native-first fallback from the retained direct strategy.
        val fields = listOf("icon-style-v12-artwork-roundness", theme, engine, variant, circle.toString(), lawnicons.toString(),
            roundnessPercent.coerceIn(0, 100).toString(), sizePercent.coerceIn(0, 100).toString(),
            generatedBorderPercent.coerceIn(0, 100).toString(), sourceStyle.id,
            lawniconsFallback.toString(), generatedArtworkRoundnessPercent.coerceIn(0, 100).toString(),
            generatedArtworkFollowIconRoundness.toString(), AppIconOverridesCodec.encode(appOverrides)) +
            disabledPackages.sorted()
        val encoded = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun current(): HyperOs4IconStyle = HyperOs4IconStyle(
            theme = ConfigManager.getThemeIdFromUi(),
            engine = ConfigManager.getColorEngineIdFromUi(),
            variant = ConfigManager.getVariantIdFromUi(),
            // Native 'circle' patches preserve the exported alpha silhouette, including
            // intermediate corners and padding in the return-animation bitmap.
            circle = true,
            lawnicons = ConfigManager.isLawniconsEnabledFromUi(),
            disabledPackages = ConfigManager.getLawniconsDisabledPackagesFromUi(),
            roundnessPercent = ConfigManager.getIconRoundnessPercentFromUi(),
            sizePercent = ConfigManager.getIconSizePercentFromUi(),
            generatedBorderPercent = Math.round(ConfigManager.getGeneratedIconBorderRatioFromUi() * 100),
            sourceStyle = ConfigManager.getDefaultIconSourceStyleFromUi(),
            appOverrides = ConfigManager.getAllAppIconOverridesFromUi(),
            lawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides()),
            generatedArtworkRoundnessPercent = ConfigManager.getGeneratedArtworkRoundnessPercentFromUi(),
            generatedArtworkFollowIconRoundness = ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi(),
        )
    }
}
