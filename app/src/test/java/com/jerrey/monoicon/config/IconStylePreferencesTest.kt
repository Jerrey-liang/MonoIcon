package com.jerrey.monoicon.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IconStylePreferencesTest {
    private val defaults = EffectiveIconStyle(IconSourceStyle.LAWNICONS, 25, 45, 100)

    @Test fun individualOverridesKeepOtherSettingsInheritedAcrossDefaultChanges() {
        val override = AppIconOverrides(sizePercent = 0, borderPercent = 0,
            generatedArtworkRoundnessPercent = 100, generatedArtworkFollowIconRoundness = true)
        assertEquals(EffectiveIconStyle(IconSourceStyle.LAWNICONS, 25, 0, 0, 100, true), override.resolve(defaults))
        assertEquals(EffectiveIconStyle(IconSourceStyle.GENERATED, 80, 0, 0, 100, true),
            override.resolve(defaults.copy(style = IconSourceStyle.GENERATED, roundnessPercent = 80)))
        assertTrue(override.hasOverrides)
        assertFalse(AppIconOverrides().hasOverrides)
    }

    @Test fun persistedValuesDistinguishInheritedFromExplicitZeroAndHundred() {
        val values = mapOf(
            "com.example.a" to AppIconOverrides(style = IconSourceStyle.NATIVE, sizePercent = 0),
            "com.example.b" to AppIconOverrides(roundnessPercent = 100, borderPercent = 0,
                generatedArtworkRoundnessPercent = 100, generatedArtworkFollowIconRoundness = false),
            "com.example.c" to AppIconOverrides(generatedArtworkFollowIconRoundness = false),
        )
        val saved = AppIconOverridesCodec.encode(values)
        assertEquals(values, AppIconOverridesCodec.decode(saved))
        assertEquals(saved, AppIconOverridesCodec.encode(values.entries.reversed().associate { it.toPair() }))
        assertTrue(values.getValue("com.example.c").hasOverrides)
    }

    @Test fun legacyFiveColumnRecordsAndStrictBooleansRemainCompatible() {
        val decoded = AppIconOverridesCodec.decode(
            "com.example.old\tgenerated\t20\t30\t40\n" +
                "com.example.new\tnative\t\t\t\t120\ttrue\n" +
                "com.example.invalid\t\t\t\t\t55\tyes",
        )
        assertEquals(AppIconOverrides(IconSourceStyle.GENERATED, 20, 30, 40), decoded["com.example.old"])
        assertEquals(AppIconOverrides(IconSourceStyle.NATIVE, generatedArtworkRoundnessPercent = 100,
            generatedArtworkFollowIconRoundness = true), decoded["com.example.new"])
        assertEquals(AppIconOverrides(generatedArtworkRoundnessPercent = 55), decoded["com.example.invalid"])
    }

    @Test fun generatedArtworkRadiusUsesIndependentOrFollowedIconRoundness() {
        assertEquals(0f, defaults.generatedArtworkRadiusFraction, 0f)
        assertEquals(0.5f, defaults.copy(generatedArtworkRoundnessPercent = 100).generatedArtworkRadiusFraction, 0f)
        assertEquals(0.3125f, defaults.copy(generatedArtworkFollowIconRoundness = true).generatedArtworkRadiusFraction, 0f)
        assertEquals(0.5f, defaults.copy(roundnessPercent = 100,
            generatedArtworkFollowIconRoundness = true).generatedArtworkRadiusFraction, 0f)
    }

    @Test fun nativeFallbackChainsHaveDistinctStableIdsAndPersistIndependently() {
        assertEquals(IconSourceStyle.NATIVE, IconSourceStyle.fromId("native"))
        assertEquals(IconSourceStyle.NATIVE_GENERATED, IconSourceStyle.fromId("native_generated"))
        val values = mapOf(
            "com.example.native" to AppIconOverrides(style = IconSourceStyle.NATIVE),
            "com.example.direct" to AppIconOverrides(style = IconSourceStyle.NATIVE_GENERATED),
        )
        assertEquals(values, AppIconOverridesCodec.decode(AppIconOverridesCodec.encode(values)))
        assertTrue(IconSourceStyle.NATIVE.permitsLawniconsFallback)
        assertTrue(IconSourceStyle.LAWNICONS.permitsLawniconsFallback)
        assertFalse(IconSourceStyle.NATIVE_GENERATED.permitsLawniconsFallback)
        assertFalse(IconSourceStyle.GENERATED.permitsLawniconsFallback)
    }

    @Test fun malformedRecordsCannotOverrideAnotherPackageOrEscapeTheAllowedRange() {
        val decoded = AppIconOverridesCodec.decode(
            "broken\ncom.example.a\tgenerated\t-3\t105\t0\ncom.example.b\tfuture\tbad\t\t\ninvalid/package\tnative\t0\t0\t0",
        )
        assertEquals(mapOf("com.example.a" to AppIconOverrides(IconSourceStyle.GENERATED, 0, 100, 0)), decoded)
        assertEquals("", AppIconOverridesCodec.encode(mapOf("com.example.a" to AppIconOverrides())))
    }
}
