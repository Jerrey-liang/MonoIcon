package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpec
import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpecs
import com.jerrey.monoicon.material2025.dynamiccolor.DynamicScheme
import com.jerrey.monoicon.material2025.dynamiccolor.Variant
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.material2025.palettes.TonalPalette
import com.jerrey.monoicon.material2025.scheme.SchemeTonalSpot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MaterialIconColorEngineTest {
    private val seed = 0xFF4285F4.toInt()

    @Test
    fun persistedEngineIdsAndDefaultRemainStable() {
        assertEquals("material_2025", IconColorEngineId.MATERIAL_2025.id)
        assertEquals("material_3", IconColorEngineId.MATERIAL_3.id)
        assertEquals(IconColorEngineId.MATERIAL_2025, IconColorEngineId.DEFAULT)
        assertEquals(IconColorEngineId.MATERIAL_3, IconColorEngineId.fromId("material_3"))
        for (unknown in listOf(null, "", "future_engine")) {
            assertEquals(IconColorEngineId.MATERIAL_2025, IconColorEngineId.fromId(unknown))
        }
    }

    @Test
    fun bothEnginesUseOfficialPalettesForEveryVariantAndAppearance() {
        val sourceHct = Hct.fromInt(seed)
        val platform = DynamicScheme.Platform.PHONE
        val specifications = mapOf(
            IconColorEngineId.MATERIAL_3 to ColorSpec.SpecVersion.SPEC_2021,
            IconColorEngineId.MATERIAL_2025 to ColorSpec.SpecVersion.SPEC_2025,
        )
        for ((engine, specVersion) in specifications) {
            val spec = ColorSpecs.get(specVersion)
            for (variant in Variant.entries) {
                for (dark in listOf(false, true)) {
                    val actual = MaterialIconColorEngine.iconColorsForSource(seed, dark, variant, engine)
                    val primary = spec.getPrimaryPalette(variant, sourceHct, dark, platform, 0.0)
                    val secondary = spec.getSecondaryPalette(variant, sourceHct, dark, platform, 0.0)
                    val message = "${engine.id} $variant dark=$dark"
                    assertEquals(message, primary.tone(if (dark) 80 else 30), actual.foreground)
                    assertEquals(message, if (dark) secondary.tone(20) else primary.tone(90), actual.background)
                    assertArrayEquals(message, tones(primary), actual.palette.accent1.tones)
                    assertArrayEquals(message, tones(secondary), actual.palette.accent2.tones)
                    assertArrayEquals(message,
                        tones(spec.getTertiaryPalette(variant, sourceHct, dark, platform, 0.0)), actual.palette.accent3.tones)
                    assertArrayEquals(message,
                        tones(spec.getNeutralPalette(variant, sourceHct, dark, platform, 0.0)), actual.palette.neutral1.tones)
                    assertArrayEquals(message,
                        tones(spec.getNeutralVariantPalette(variant, sourceHct, dark, platform, 0.0)), actual.palette.neutral2.tones)
                }
            }
        }
    }

    @Test
    fun expressiveEngineSwitchChangesBothIconColors() {
        for (dark in listOf(false, true)) {
            val material3 = Material3ColorEngine.iconColorsForSource(seed, dark, Variant.EXPRESSIVE)
            val material2025 = Material2025ColorEngine.iconColorsForSource(seed, dark, Variant.EXPRESSIVE)
            assertNotEquals("foreground dark=$dark", material2025.foreground, material3.foreground)
            assertNotEquals("background dark=$dark", material2025.background, material3.background)
        }
    }

    @Test
    fun defaultAndLegacyApiKeepMaterial2025IconTones() {
        for (dark in listOf(false, true)) {
            val reference = SchemeTonalSpot(
                Hct.fromInt(seed), dark, 0.0, ColorSpec.SpecVersion.SPEC_2025, DynamicScheme.Platform.PHONE,
            )
            val actual = MaterialIconColorEngine.iconColorsForSource(seed, dark)
            val legacy = Material2025ColorEngine.iconColorsForSource(seed, dark)
            assertEquals(reference.primaryPalette.tone(if (dark) 80 else 30), actual.foreground)
            assertEquals(if (dark) reference.secondaryPalette.tone(20) else reference.primaryPalette.tone(90), actual.background)
            assertEquals(actual.foreground, legacy.foreground)
            assertEquals(actual.background, legacy.background)
        }
        assertEquals(Variant.TONAL_SPOT, Material2025ColorEngine.variantFromId(null))
        assertEquals(Variant.TONAL_SPOT, Material2025ColorEngine.variantFromId("future_variant"))
    }

    @Test
    fun cacheRequestIncludesEveryColorInput() {
        val request = IconColorRequest(seed, false, Variant.TONAL_SPOT, IconColorEngineId.MATERIAL_2025)
        assertEquals(request, request.copy())
        assertNotEquals(request, request.copy(engine = IconColorEngineId.MATERIAL_3))
        assertNotEquals(request, request.copy(variant = Variant.EXPRESSIVE))
        assertNotEquals(request, request.copy(sourceColor = 0xFFFF0000.toInt()))
        assertNotEquals(request, request.copy(dark = true))
    }

    private fun tones(palette: TonalPalette): IntArray =
        intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100).map(palette::tone).toIntArray()
}
