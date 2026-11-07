package com.jerrey.monoicon

import android.app.WallpaperColors
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpec
import com.jerrey.monoicon.material2025.dynamiccolor.DynamicScheme
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.material2025.scheme.SchemeExpressive
import com.jerrey.monoicon.material2025.scheme.SchemeTonalSpot
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The runtime now derives Material 2025 palettes from WallpaperColors; it does
 * not read OEM system_accent resources. Exercise that adapter with explicit
 * wallpaper snapshots, without replacing live wallpaper or user preferences.
 */
@RunWith(AndroidJUnit4::class)
class LauncherOverlayColorInstrumentedTest {
    private val savedEngineState = mutableMapOf<String, Any?>()

    @Before
    fun saveRuntimeState() {
        for (name in listOf("launcherContext", "lastLoggedColors", "lastLoggedWallpaper",
            "cachedWallpaperSeed", "cachedWallpaperSeedAt")) {
            savedEngineState[name] = field(name).get(null)
        }
    }

    @After
    fun restoreRuntimeState() {
        // In particular, never leave a synthetic Context in the process-wide
        // engine: later Compose tests use this same object to choose their seed.
        savedEngineState.forEach { (name, value) -> field(name).set(null, value) }
    }

    @Test
    fun chromaticWallpaperCandidateUsesMaterial2025LightAndDarkIconTones() {
        val seed = 0xFF4285F4.toInt()
        val colors = WallpaperColors(Color.valueOf(0xFF808080.toInt()), Color.valueOf(seed), Color.valueOf(Color.RED))
        for (dark in listOf(false, true)) {
            val actual = calculate(colors, dark, "tonal_spot")
            val reference = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0, SPEC, PLATFORM)
            assertIconTones(reference, dark, actual)
        }
    }

    @Test
    fun subsequentWallpaperAndVariantChangesProduceTheirOwnPalette() {
        val red = 0xFFF44336.toInt()
        val blue = 0xFF4285F4.toInt()
        for (dark in listOf(false, true)) {
            val first = calculate(WallpaperColors(Color.valueOf(red), null, null), dark, "tonal_spot")
            val second = calculate(WallpaperColors(Color.valueOf(blue), null, null), dark, "tonal_spot")
            val expressive = calculate(WallpaperColors(Color.valueOf(blue), null, null), dark, "expressive")
            assertIconTones(SchemeTonalSpot(Hct.fromInt(red), dark, 0.0, SPEC, PLATFORM), dark, first)
            assertIconTones(SchemeTonalSpot(Hct.fromInt(blue), dark, 0.0, SPEC, PLATFORM), dark, second)
            assertIconTones(SchemeExpressive(Hct.fromInt(blue), dark, 0.0, SPEC, PLATFORM), dark, expressive)
            assertNotEquals("A new wallpaper must not reuse the previous palette", first.foreground, second.foreground)
            assertNotEquals("A new variant must not reuse the previous palette", second.foreground, expressive.foreground)
        }
    }

    @Test
    fun neutralWallpaperKeepsItsPrimaryCandidate() {
        val seed = 0xFF808080.toInt()
        val colors = WallpaperColors(Color.valueOf(seed), Color.valueOf(Color.WHITE), Color.valueOf(Color.BLACK))
        for (dark in listOf(false, true)) {
            assertIconTones(SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0, SPEC, PLATFORM), dark,
                calculate(colors, dark, "tonal_spot"))
        }
    }

    @Test
    fun uninitializedRuntimeUsesFixedNeutralPair() {
        field("launcherContext").set(null, null)
        val colors = PixelMonetColorEngine.getIconColors()
        assertEquals(0xFF3C4043.toInt(), colors.foreground)
        assertEquals(0xFFF5F5F5.toInt(), colors.background)
    }

    private fun calculate(colors: WallpaperColors, dark: Boolean, variant: String): IconThemeColors =
        PixelMonetColorEngine::class.java.getDeclaredMethod("calculate", String::class.java,
            WallpaperColors::class.java, Boolean::class.javaPrimitiveType, String::class.java)
            .apply { isAccessible = true }
            .invoke(PixelMonetColorEngine, "instrumentation_fixture", colors, dark, variant) as IconThemeColors

    private fun assertIconTones(reference: DynamicScheme, dark: Boolean, actual: IconThemeColors) {
        // AOSP icon contract, independently of Material2025ColorEngine's mapping:
        // light primary 30/90; dark primary 80 and secondary 20.
        assertEquals(reference.primaryPalette.tone(if (dark) 80 else 30), actual.foreground)
        assertEquals(if (dark) reference.secondaryPalette.tone(20) else reference.primaryPalette.tone(90), actual.background)
    }

    private fun field(name: String) = PixelMonetColorEngine::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        val SPEC = ColorSpec.SpecVersion.SPEC_2025
        val PLATFORM = DynamicScheme.Platform.PHONE
    }
}
