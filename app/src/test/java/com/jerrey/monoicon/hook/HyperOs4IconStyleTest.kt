package com.jerrey.monoicon.hook

import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.IconSourceStyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HyperOs4IconStyleTest {
    private val original = HyperOs4IconStyle(
        "pixel_default", "material_2025", "tonal_spot", false, true, emptySet(),
        sourceStyle = IconSourceStyle.LAWNICONS,
    )

    @Test fun containedBorderInvalidatesPreviouslyExportedIcons() {
        // Published v8 signature for these settings, before complete-artwork containment.
        val previousExport = "c9c0030331d99ea4bd0b8057c1044eaf2465be717a6e7fd5e8b6c694f160d702"
        assertNotEquals(previousExport, original.signature())
    }

    @Test fun changingAnAppSelectionRequiresNewExportEvenWhenPaletteIsUnchanged() {
        val excluded = original.copy(disabledPackages = setOf("com.example.app"))
        assertNotEquals(original.signature(), excluded.signature())
        assertEquals(original.signature(), excluded.copy(disabledPackages = emptySet()).signature())
    }

    @Test fun preferenceSetIterationOrderDoesNotInvalidateAnOtherwiseIdenticalGeneration() {
        assertEquals(
            original.copy(disabledPackages = linkedSetOf("com.example.a", "com.example.b")).signature(),
            original.copy(disabledPackages = linkedSetOf("com.example.b", "com.example.a")).signature(),
        )
    }

    @Test fun switchingEngineOrVariantInvalidatesEvenVariantsWithMatchingColorOutput() {
        assertNotEquals(original.signature(), original.copy(engine = "material_3").signature())
        assertNotEquals(original.signature(), original.copy(variant = "monochrome").signature())
        assertNotEquals(original.signature(), original.copy(circle = true).signature())
        assertNotEquals(original.signature(), original.copy(lawnicons = false).signature())
    }

    @Test fun eachGeometryControlRequiresNewImagesWithTheSamePalette() {
        val baseline = original.copy(circle = true, roundnessPercent = 0, sizePercent = 0)
        assertNotEquals(baseline.signature(), baseline.copy(roundnessPercent = 50).signature())
        assertNotEquals(baseline.signature(), baseline.copy(roundnessPercent = 100).signature())
        assertNotEquals(baseline.signature(), baseline.copy(sizePercent = 50).signature())
        assertNotEquals(baseline.signature(), baseline.copy(sizePercent = 100).signature())
    }

    @Test fun shrinkingGeneratedBorderRequiresNewImagesEvenWithTheSamePaletteAndGeometry() {
        assertNotEquals(original.signature(), original.copy(generatedBorderPercent = 50).signature())
        assertNotEquals(original.signature(), original.copy(generatedBorderPercent = 0).signature())
        assertEquals(original.signature(), original.copy(generatedBorderPercent = 100).signature())
    }

    @Test fun artworkRoundnessAndFollowingOuterCornersInvalidateExportedImages() {
        assertNotEquals(original.signature(), original.copy(generatedArtworkRoundnessPercent = 50).signature())
        assertNotEquals(original.signature(), original.copy(generatedArtworkFollowIconRoundness = true).signature())
        assertEquals(original.copy(generatedArtworkRoundnessPercent = 100).signature(),
            original.copy(generatedArtworkRoundnessPercent = 150).signature())
    }

    @Test fun globalPriorityAndEveryPerAppParameterInvalidateExports() {
        assertNotEquals(original.signature(), original.copy(sourceStyle = IconSourceStyle.NATIVE).signature())
        assertNotEquals(original.copy(sourceStyle = IconSourceStyle.NATIVE).signature(),
            original.copy(sourceStyle = IconSourceStyle.NATIVE_GENERATED).signature())
        assertNotEquals(original.signature(), original.copy(lawniconsFallback = false).signature())
        for (override in listOf(AppIconOverrides(style = IconSourceStyle.GENERATED),
            AppIconOverrides(roundnessPercent = 10), AppIconOverrides(sizePercent = 0),
            AppIconOverrides(borderPercent = 0), AppIconOverrides(generatedArtworkRoundnessPercent = 50),
            AppIconOverrides(generatedArtworkFollowIconRoundness = false))) {
            assertNotEquals(original.signature(), original.copy(appOverrides = mapOf("com.example.a" to override)).signature())
        }
        val a = "com.example.a" to AppIconOverrides(sizePercent = 60)
        val b = "com.example.b" to AppIconOverrides(style = IconSourceStyle.NATIVE)
        assertEquals(original.copy(appOverrides = linkedMapOf(a, b)).signature(),
            original.copy(appOverrides = linkedMapOf(b, a)).signature())
    }
}
