package com.jerrey.monoicon

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicit opt-in: exercises the real app -> root -> launcher transport and restores settings. */
@RunWith(AndroidJUnit4::class)
class HyperOs4DeviceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val launcherFiles = "/data/user/${android.os.Process.myUid() / 100000}/com.miui.home/files"

    private fun root(command: String): ByteArray {
        val process = ProcessBuilder("su", "-M", "-c", command).start()
        val bytes = process.inputStream.readBytes()
        assertTrue("root timed out", process.waitFor(15, TimeUnit.SECONDS))
        assertEquals("root failed: ${process.errorStream.bufferedReader().readText()}", 0, process.exitValue())
        return bytes
    }

    private fun commit(): List<String> {
        assertTrue("configuration must commit in the global namespace", HyperOs4PalettePublisher.flushBeforeRestart(context))
        val content = root("cat $launcherFiles/monoicon_palette.txt").toString(Charsets.UTF_8)
        repeat(30) {
            val applied = root("cat $launcherFiles/monoicon_palette.applied").toString(Charsets.UTF_8)
            if (content == applied) return content.trim().split(' ')
            Thread.sleep(200)
        }
        fail("launcher did not acknowledge $content")
        return emptyList()
    }

    private fun icon(config: List<String>, name: String): Bitmap {
        val bytes = root("cat $launcherFiles/monoicon_icons/${config[7]}/$name.png")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }

    private fun screenshot(name: String) {
        // HyperOS may freeze the test process as soon as HOME covers it. Keep
        // capture and bringing the app back in the same independent root child.
        val file = File(context.cacheDir, "hyperos4-$name.png")
        root("killall com.miui.home; am start -W -n com.miui.home/.launcher.Launcher >/dev/null; input keyevent 3; " +
            "sleep 3; screencap -p '${file.path}'; chown ${android.os.Process.myUid()}:${android.os.Process.myUid()} '${file.path}'; " +
            "am start -n com.jerrey.monoicon/.MainActivity >/dev/null")
        assertTrue(file.length() > 8)
    }

    @Test fun realPublicationShapePaletteLawniconsAndDisableRoundTrip() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hyperos4Device") == "true")
        val enabled = ConfigManager.isEnabledFromUi()
        val circle = ConfigManager.isCircleIconsEnabledFromUi()
        val lawnicons = ConfigManager.isLawniconsEnabledFromUi()
        val variant = ConfigManager.getVariantIdFromUi()
        try {
            ConfigManager.setEnabled(true)
            ConfigManager.setCircleIconsEnabled(true)
            ConfigManager.setLawniconsEnabled(true)
            ConfigManager.setVariantId("tonal_spot")
            val first = commit()
            val rounded = icon(first, "com.jerrey.monoicon.MainActivity")
            assertEquals(0, Color.alpha(rounded.getPixel(0, 0)))
            assertTrue(Color.alpha(rounded.getPixel(96, 96)) > 220)
            rounded.recycle()
            screenshot("circle")

            ConfigManager.setVariantId("expressive")
            ConfigManager.setLawniconsEnabled(false)
            val second = commit()
            assertNotEquals(first[7], second[7])
            assertNotEquals(first.subList(3, 5), second.subList(3, 5))
            assertEquals("0", second[6])
            screenshot("expressive-aosp")

            ConfigManager.setCircleIconsEnabled(false)
            assertEquals("0", commit()[5])
            screenshot("system-shape")

            ConfigManager.setEnabled(false)
            val disabled = commit()
            assertEquals("0", disabled[2])
            assertTrue(root("cat $launcherFiles/monoicon_hyos_palette.txt").toString(Charsets.UTF_8).contains("result=12"))
            screenshot("disabled")
        } finally {
            ConfigManager.setEnabled(enabled)
            ConfigManager.setCircleIconsEnabled(circle)
            ConfigManager.setLawniconsEnabled(lawnicons)
            ConfigManager.setVariantId(variant)
            commit()
            screenshot("restored")
        }
    }
}
