package com.jerrey.monoicon.ui.screens

import com.jerrey.monoicon.config.IconSourceStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class IconSourceCapabilitiesTest {
    private val app = LawniconsApp("com.example.app", "App", "com.example.app/.MainActivity")

    @Test
    fun availableStylesOnlyIncludePresentAuthoredResources() {
        assertEquals(listOf(IconSourceStyle.GENERATED), availableIconSourceStyles(app))
        assertEquals(listOf(IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED, IconSourceStyle.GENERATED),
            availableIconSourceStyles(app.copy(hasNativeMonochrome = true)))
        assertEquals(listOf(IconSourceStyle.LAWNICONS, IconSourceStyle.GENERATED),
            availableIconSourceStyles(app.copy(hasLawnicons = true)))
        assertEquals(IconSourceStyle.entries.toList(),
            availableIconSourceStyles(app.copy(hasNativeMonochrome = true, hasLawnicons = true)))
    }

    @Test
    fun customStylesExcludeTheResolvedDefaultWithoutReorderingTheOthers() {
        val allSources = app.copy(hasNativeMonochrome = true, hasLawnicons = true)
        assertEquals(listOf(IconSourceStyle.LAWNICONS, IconSourceStyle.GENERATED),
            customIconSourceStyles(allSources, IconSourceStyle.NATIVE))
        assertEquals(listOf(IconSourceStyle.GENERATED),
            customIconSourceStyles(allSources, IconSourceStyle.NATIVE, allowLawniconsFallback = false))
        assertEquals(listOf(IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED, IconSourceStyle.GENERATED),
            customIconSourceStyles(allSources, IconSourceStyle.LAWNICONS))
        assertEquals(listOf(IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED, IconSourceStyle.LAWNICONS),
            customIconSourceStyles(allSources, IconSourceStyle.GENERATED))
        assertEquals(listOf(IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED),
            customIconSourceStyles(app.copy(hasNativeMonochrome = true), IconSourceStyle.GENERATED))
        assertEquals(emptyList<IconSourceStyle>(),
            customIconSourceStyles(app, IconSourceStyle.GENERATED))
    }

    @Test
    fun oldPreferredSourceResolvesUsingItsActualAvailableFallback() {
        assertEquals(IconSourceStyle.GENERATED, resolvedAppIconSource(app, IconSourceStyle.NATIVE, true))
        val lawnicons = app.copy(hasLawnicons = true)
        assertEquals(IconSourceStyle.LAWNICONS, resolvedAppIconSource(lawnicons, IconSourceStyle.NATIVE, true))
        assertEquals(IconSourceStyle.GENERATED, resolvedAppIconSource(lawnicons, IconSourceStyle.NATIVE, false))
        assertEquals(IconSourceStyle.GENERATED,
            resolvedAppIconSource(lawnicons, IconSourceStyle.NATIVE_GENERATED, true))
        assertEquals(IconSourceStyle.LAWNICONS, resolvedAppIconSource(lawnicons, IconSourceStyle.LAWNICONS, true))
        assertEquals(IconSourceStyle.NATIVE,
            resolvedAppIconSource(app.copy(hasNativeMonochrome = true), IconSourceStyle.LAWNICONS, true))
        assertEquals(IconSourceStyle.GENERATED, resolvedAppIconSource(lawnicons, IconSourceStyle.LAWNICONS, false))
        assertEquals(IconSourceStyle.GENERATED,
            resolvedAppIconSource(app.copy(hasNativeMonochrome = true, hasLawnicons = true), IconSourceStyle.GENERATED, true))
    }
}
