package com.jerrey.monoicon

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherOverlayColorInstrumentedTest {

    @Test
    fun missingLauncherResourcesUseSystemAccentFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PixelMonetColorEngine.init(context)

        val colors = PixelMonetColorEngine.getIconColors()
        val dark = isDark(context)

        assertEquals(
            context.getColor(
                if (dark) android.R.color.system_accent1_200
                else android.R.color.system_accent1_600
            ),
            colors.foreground,
        )
        assertEquals(
            context.getColor(
                if (dark) android.R.color.system_accent1_900
                else android.R.color.system_accent1_50
            ),
            colors.background,
        )
    }

    @Test
    fun rgbValuesAreReadAgainForEveryCall() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ChangingSystemColorContext(base)
        PixelMonetColorEngine.init(context)

        val first = PixelMonetColorEngine.getIconColors()
        val second = PixelMonetColorEngine.getIconColors()

        assertNotEquals(first.foreground, second.foreground)
        assertNotEquals(first.background, second.background)
    }

    @Test
    fun resourceFailureUsesFixedNeutralPair() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = ThrowingColorContext(base)
        PixelMonetColorEngine.init(context)

        val colors = PixelMonetColorEngine.getIconColors()

        if (isDark(context)) {
            assertEquals(0xFFDADCE0.toInt(), colors.foreground)
            assertEquals(0xFF202124.toInt(), colors.background)
        } else {
            assertEquals(0xFF3C4043.toInt(), colors.foreground)
            assertEquals(0xFFF5F5F5.toInt(), colors.background)
        }
    }

    private fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private class ChangingSystemColorContext(base: Context) : ContextWrapper(base) {
        private val changingResources = ChangingResources(base.resources)

        override fun getApplicationContext(): Context = this
        override fun getResources(): Resources = changingResources
    }

    private class ThrowingColorContext(base: Context) : ContextWrapper(base) {
        private val throwingResources = ThrowingResources(base.resources)

        override fun getApplicationContext(): Context = this
        override fun getResources(): Resources = throwingResources
    }

    @Suppress("DEPRECATION")
    private class ChangingResources(base: Resources) : Resources(
        base.assets,
        base.displayMetrics,
        base.configuration,
    ) {
        private var generation = 0

        override fun getColor(id: Int, theme: Theme?): Int {
            generation++
            val channel = generation and 0xFF
            return 0xFF000000.toInt() or (channel shl 16) or (channel shl 8) or channel
        }
    }

    @Suppress("DEPRECATION")
    private class ThrowingResources(base: Resources) : Resources(
        base.assets,
        base.displayMetrics,
        base.configuration,
    ) {
        override fun getColor(id: Int, theme: Theme?): Int {
            throw NotFoundException("test")
        }
    }
}
