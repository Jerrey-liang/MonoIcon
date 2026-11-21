package com.jerrey.monoicon

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpec
import com.jerrey.monoicon.material2025.dynamiccolor.DynamicScheme
import com.jerrey.monoicon.material2025.hct.Hct
import com.jerrey.monoicon.material2025.scheme.SchemeExpressive
import com.jerrey.monoicon.material2025.scheme.SchemeTonalSpot
import com.jerrey.monoicon.theme.color.dynamic.IconColorEngineId
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import com.jerrey.monoicon.theme.wallpaper.WallpaperImagePolicy
import com.jerrey.monoicon.theme.wallpaper.WallpaperProvenance
import com.jerrey.monoicon.theme.wallpaper.WallpaperSnapshot
import com.jerrey.monoicon.theme.wallpaper.WallpaperStatus
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Own-image seeds feed the launcher palette; OEM WallpaperColors/overlays are not inputs. */
@RunWith(AndroidJUnit4::class)
class LauncherOverlayColorInstrumentedTest {
    private val saved = mutableMapOf<Field, Any?>()
    private val preferences = mutableMapOf<String, String>()
    private val images = mutableListOf<Bitmap>()

    @Before fun isolateRuntimePreferencesAndPalette() {
        listOf("cachedIconColors", "lastLoggedColors").forEach { save(engineField(it)) }
        listOf("remotePrefsProvider", "cachedColorEngineId", "lastColorEngineRefreshMs")
            .forEach { save(configField(it)) }
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> preferences[args!![0] as String] ?: args[1]
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences
        val provider: () -> SharedPreferences? = { prefs }
        configField("remotePrefsProvider").set(null, provider)
        engineField("cachedIconColors").set(null, null)
    }

    @After fun restoreRuntimeState() {
        saved.forEach { (field, value) ->
            val current = field.get(null)
            if (current is AtomicLong) current.set(value as Long) else field.set(null, value)
        }
        images.forEach(Bitmap::recycle)
    }

    @Test fun chromaticImageCandidateSuppliesMaterial2025LightAndDarkIconTones() {
        val image = image(Color.GRAY)
        Canvas(image).drawRect(0f, 0f, 32f, 32f, Paint().apply { color = BLUE })
        val wallpaper = snapshot(image)
        assertNotEquals("color scoring must use the image's chromatic pixels", Color.GRAY, wallpaper.seedColor)
        for (dark in listOf(false, true)) {
            assertIconTones(SchemeTonalSpot(Hct.fromInt(wallpaper.seedColor), dark, 0.0, SPEC, PLATFORM),
                dark, calculate(wallpaper, dark, "tonal_spot"))
        }
    }

    @Test fun subsequentImageAndVariantChangesDoNotReusePreviousPalette() {
        val red = snapshot(image(0xFFF44336.toInt()))
        val blue = snapshot(image(BLUE))
        for (dark in listOf(false, true)) {
            val first = calculate(red, dark, "tonal_spot")
            val second = calculate(blue, dark, "tonal_spot")
            val expressive = calculate(blue, dark, "expressive")
            assertIconTones(SchemeTonalSpot(Hct.fromInt(red.seedColor), dark, 0.0, SPEC, PLATFORM), dark, first)
            assertIconTones(SchemeTonalSpot(Hct.fromInt(blue.seedColor), dark, 0.0, SPEC, PLATFORM), dark, second)
            assertIconTones(SchemeExpressive(Hct.fromInt(blue.seedColor), dark, 0.0, SPEC, PLATFORM), dark, expressive)
            assertNotEquals("new image", first.foreground, second.foreground)
            assertNotEquals("new variant", second.foreground, expressive.foreground)
        }
    }

    @Test fun neutralImageKeepsItsOwnDominantSeed() {
        val wallpaper = snapshot(image(Color.GRAY))
        assertEquals("neutral images do not adopt the chromatic default seed", Color.GRAY, wallpaper.seedColor)
        for (dark in listOf(false, true)) {
            assertIconTones(SchemeTonalSpot(Hct.fromInt(wallpaper.seedColor), dark, 0.0, SPEC, PLATFORM),
                dark, calculate(wallpaper, dark, "tonal_spot"))
        }
    }

    @Test fun unavailableImageUsesTheSameStableFallbackSeedForItsPalette() {
        val fallback = WallpaperSnapshot(null, WallpaperImagePolicy.STABLE_FALLBACK_COLOR,
            WallpaperProvenance.STABLE_FALLBACK, WallpaperStatus.UNAVAILABLE, 0, "Image unavailable")
        for (dark in listOf(false, true)) {
            assertIconTones(SchemeTonalSpot(Hct.fromInt(fallback.seedColor), dark, 0.0, SPEC, PLATFORM),
                dark, calculate(fallback, dark, "tonal_spot"))
        }
    }

    @Test fun runtimeCacheIncludesEngineVariantAndDarkModeWithoutExternalInvalidation() {
        val wallpaper = snapshot(image(BLUE))
        val light = calculate(wallpaper, false, "expressive")
        assertSame(light, calculate(wallpaper.copy(revision = 2, provenance = WallpaperProvenance.CACHED_IMAGE), false, "expressive"))
        val dark = calculate(wallpaper, true, "expressive")
        assertSame(dark, calculate(wallpaper, true, "expressive"))
        assertNotEquals("dark mode", light.foreground, dark.foreground)
        for (isDark in listOf(false, true)) {
            val original = calculate(wallpaper, isDark, "expressive")
            val material3 = calculate(wallpaper, isDark, "expressive", IconColorEngineId.MATERIAL_3)
            assertSame(material3, calculate(wallpaper, isDark, "expressive", IconColorEngineId.MATERIAL_3))
            assertIconTones(SchemeExpressive(Hct.fromInt(wallpaper.seedColor), isDark, 0.0,
                ColorSpec.SpecVersion.SPEC_2021, PLATFORM), isDark, material3)
            assertNotEquals("specification foreground", original.foreground, material3.foreground)
            assertNotEquals("specification background", original.background, material3.background)
            val restored = calculate(wallpaper, isDark, "expressive")
            assertEquals(original.foreground, restored.foreground)
            assertEquals(original.background, restored.background)
            val tonal = calculate(wallpaper, isDark, "tonal_spot")
            assertNotEquals("variant", restored.foreground, tonal.foreground)
        }
    }

    private fun calculate(wallpaper: WallpaperSnapshot, dark: Boolean, variant: String,
        engine: IconColorEngineId = IconColorEngineId.MATERIAL_2025): IconThemeColors {
        preferences["variant_id"] = variant
        preferences["color_engine_id"] = engine.id
        // Preserve the engine's warm palette cache. ConfigManager's own cache-change
        // invalidation would hide a missing engine dimension in IconColorRequest.
        configField("cachedColorEngineId").set(null, engine.id)
        return PixelMonetColorEngine::class.java.getDeclaredMethod("colorsForSnapshot",
            WallpaperSnapshot::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(PixelMonetColorEngine, wallpaper, dark) as IconThemeColors
    }

    private fun image(color: Int): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color); images += this }

    private fun snapshot(image: Bitmap): WallpaperSnapshot = WallpaperSnapshot(image,
        requireNotNull(PixelMonetColorEngine.seedFromBitmap(image)), WallpaperProvenance.WALLPAPER_FILE,
        WallpaperStatus.READY, 1, "In-memory wallpaper image")

    private fun assertIconTones(reference: DynamicScheme, dark: Boolean, actual: IconThemeColors) {
        assertEquals(reference.primaryPalette.tone(if (dark) 80 else 30), actual.foreground)
        assertEquals(if (dark) reference.secondaryPalette.tone(20) else reference.primaryPalette.tone(90), actual.background)
    }

    private fun save(field: Field) {
        val value = field.get(null)
        saved[field] = if (value is AtomicLong) value.get() else value
    }
    private fun engineField(name: String): Field = PixelMonetColorEngine::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun configField(name: String): Field = ConfigManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private companion object {
        const val BLUE = 0xFF4285F4.toInt()
        val SPEC = ColorSpec.SpecVersion.SPEC_2025
        val PLATFORM = DynamicScheme.Platform.PHONE
    }
}
