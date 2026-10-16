package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.hook.HyperOs4IconExporter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HyperOs4ExportInstrumentedTest {
    @Test fun installedLauncherActivitiesExportWithoutAbortingTheBatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hyperos4-test-export")
        try {
            val result = HyperOs4IconExporter.export(context, directory) { true }!!
            assertTrue("at least this application's activity must export", result.components > 0)
            assertTrue(File(directory, "com.jerrey.monoicon.MainActivity.png").length() > 8)
        } finally {
            directory.deleteRecursively()
        }
    }
}
