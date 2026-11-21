package com.jerrey.monoicon.ui.screens

import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.IconSourceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconFiltersTest {
    private val app = LawniconsApp("com.Example.camera", "Camera 相机", "com.Example.camera/Main", true, false)

    @Test
    fun artworkFieldsDistinguishExplicitZeroAndFalseFromInheritance() {
        val explicit = AppIconOverrides(generatedArtworkRoundnessPercent = 0, generatedArtworkFollowIconRoundness = false)
        for (filter in OverrideFilter.entries) {
            for (filters in listOf(AppIconFilters(artworkRoundness = filter), AppIconFilters(artworkFollowRoundness = filter))) {
                assertEquals(filter != OverrideFilter.CUSTOM, filters.matches(app, AppIconOverrides(), ""))
                assertEquals(filter != OverrideFilter.DEFAULT, filters.matches(app, explicit, ""))
            }
        }
        val both = AppIconFilters(artworkRoundness = OverrideFilter.CUSTOM, artworkFollowRoundness = OverrideFilter.CUSTOM)
        assertEquals(2, both.activeCount)
        assertTrue(both.matches(app, explicit, ""))
        assertFalse(both.matches(app, explicit.copy(generatedArtworkFollowIconRoundness = null), ""))
    }

    @Test
    fun searchesLabelAndPackageIgnoringCaseAndOuterWhitespace() {
        val filters = AppIconFilters()
        for (query in listOf("", "  ", "相机", "camera", "  COM.example  ")) {
            assertTrue(query, filters.matches(app, AppIconOverrides(), query))
        }
        assertFalse(filters.matches(app, AppIconOverrides(), "gallery"))
    }

    @Test
    fun eachParameterDistinguishesInheritanceFromAnExplicitDefaultValue() {
        val custom = AppIconOverrides(IconSourceStyle.NATIVE, 0, 100, 100)
        for (filter in OverrideFilter.entries) {
            val matchesDefault = filter != OverrideFilter.CUSTOM
            val matchesCustom = filter != OverrideFilter.DEFAULT
            for (filters in listOf(AppIconFilters(style = filter), AppIconFilters(roundness = filter),
                AppIconFilters(size = filter), AppIconFilters(border = filter))) {
                assertEquals(matchesDefault, filters.matches(app, AppIconOverrides(), ""))
                assertEquals(matchesCustom, filters.matches(app, custom, ""))
            }
        }
    }

    @Test
    fun settingsAndResourceFiltersIntersectInsteadOfReplacingEachOther() {
        val filters = AppIconFilters(style = OverrideFilter.CUSTOM, roundness = OverrideFilter.DEFAULT,
            size = OverrideFilter.CUSTOM, border = OverrideFilter.DEFAULT,
            native = ResourceFilter.AVAILABLE, lawnicons = ResourceFilter.UNAVAILABLE)
        val value = AppIconOverrides(style = IconSourceStyle.GENERATED, sizePercent = 35)
        assertEquals(6, filters.activeCount)
        assertTrue(filters.matches(app, value, "相机"))
        assertFalse(filters.matches(app.copy(hasLawnicons = true), value, ""))
        assertFalse(filters.matches(app.copy(hasNativeMonochrome = false), value, ""))
        assertFalse(filters.matches(app, value.copy(roundnessPercent = 0), ""))
        assertFalse(filters.matches(app, value.copy(sizePercent = null), ""))
        assertFalse(filters.matches(app, value.copy(borderPercent = 100), ""))
        assertFalse(filters.matches(app, value, "wrong package"))
    }

    @Test
    fun bothResourceFiltersSupportAllAvailableAndUnavailable() {
        for (filter in ResourceFilter.entries) {
            assertEquals(filter != ResourceFilter.UNAVAILABLE,
                AppIconFilters(native = filter).matches(app, AppIconOverrides(), ""))
            assertEquals(filter != ResourceFilter.AVAILABLE,
                AppIconFilters(lawnicons = filter).matches(app, AppIconOverrides(), ""))
        }
        assertEquals(0, AppIconFilters().activeCount)
    }

    @Test
    fun resourceCoverageDoesNotChangeWhenTheSelectedSourceChanges() {
        val nativeOnly = AppIconFilters(native = ResourceFilter.AVAILABLE, lawnicons = ResourceFilter.UNAVAILABLE)
        val lawniconsOnly = AppIconFilters(native = ResourceFilter.UNAVAILABLE, lawnicons = ResourceFilter.AVAILABLE)
        for (source in IconSourceStyle.entries) {
            val settings = AppIconOverrides(style = source)
            assertTrue(nativeOnly.matches(app, settings, ""))
            assertFalse(lawniconsOnly.matches(app, settings, ""))
            val mapped = app.copy(hasNativeMonochrome = false, hasLawnicons = true)
            assertTrue(lawniconsOnly.matches(mapped, settings, ""))
            assertFalse(nativeOnly.matches(mapped, settings, ""))
        }
    }
}
