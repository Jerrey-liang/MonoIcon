package com.jerrey.monoicon.ui.screens

import com.jerrey.monoicon.config.AppIconOverrides

internal enum class OverrideFilter {
    ALL, DEFAULT, CUSTOM;
    fun matches(customized: Boolean) = this == ALL || (this == CUSTOM) == customized
}

internal enum class ResourceFilter {
    ALL, AVAILABLE, UNAVAILABLE;
    fun matches(available: Boolean) = this == ALL || (this == AVAILABLE) == available
}

/** All filters combine with AND; search matches either label or package. */
internal data class AppIconFilters(
    val style: OverrideFilter = OverrideFilter.ALL,
    val roundness: OverrideFilter = OverrideFilter.ALL,
    val size: OverrideFilter = OverrideFilter.ALL,
    val border: OverrideFilter = OverrideFilter.ALL,
    val native: ResourceFilter = ResourceFilter.ALL,
    val lawnicons: ResourceFilter = ResourceFilter.ALL,
    val artworkRoundness: OverrideFilter = OverrideFilter.ALL,
    val artworkFollowRoundness: OverrideFilter = OverrideFilter.ALL,
) {
    val activeCount: Int get() = listOf(style, roundness, size, border, artworkRoundness, artworkFollowRoundness).count { it != OverrideFilter.ALL } +
        listOf(native, lawnicons).count { it != ResourceFilter.ALL }

    fun matches(app: LawniconsApp, overrides: AppIconOverrides, query: String): Boolean {
        val search = query.trim()
        return (search.isBlank() || app.label.contains(search, ignoreCase = true) ||
            app.packageName.contains(search, ignoreCase = true)) &&
            style.matches(overrides.style != null) && roundness.matches(overrides.roundnessPercent != null) &&
            size.matches(overrides.sizePercent != null) && border.matches(overrides.borderPercent != null) &&
            artworkRoundness.matches(overrides.generatedArtworkRoundnessPercent != null) &&
            artworkFollowRoundness.matches(overrides.generatedArtworkFollowIconRoundness != null) &&
            native.matches(app.hasNativeMonochrome) && lawnicons.matches(app.hasLawnicons)
    }
}
