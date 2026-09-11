package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ConfigManager.init()` idempotency (Phase 12 cleanup).
 *
 * The UI entry point is `MainActivity.onCreate`, which runs again on every
 * configuration change; before the guard each run registered another
 * `XposedServiceHelper.OnServiceListener` that the framework never releases.
 */
@RunWith(AndroidJUnit4::class)
class ConfigManagerInitInstrumentedTest {

    @Test
    fun initIsIdempotent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // First call may already have happened if another test ran first, so
        // assert the invariant "one registration per process" instead of "1".
        val before = ConfigManager.uiInitRegistrationCount()
        ConfigManager.init(context)
        ConfigManager.init(context)
        ConfigManager.init(context)
        val after = ConfigManager.uiInitRegistrationCount()

        assertEquals("exactly one registration per process", 1, after)
        assertTrue(
            "three calls must add at most one registration (before=$before, after=$after)",
            after - before <= 1,
        )

        // The fallback preferences must still be usable for the UI readers.
        assertTrue(ConfigManager.isEnabledFromUi())
        assertFalse(ConfigManager.getVariantIdFromUi().isBlank())
    }
}
