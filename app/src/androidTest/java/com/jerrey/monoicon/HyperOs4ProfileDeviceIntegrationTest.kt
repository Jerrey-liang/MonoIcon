package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.profile.ProfileRepository
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** One opt-in check of this refactor's transport. No settings changes or restart. */
@RunWith(AndroidJUnit4::class)
class HyperOs4ProfileDeviceIntegrationTest {
    @Test fun selectedProfileIsPublishedAndAcknowledgedByNativeEngine() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hyperos4ProfileDevice") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolution = ProfileRepository.resolve(context)
        val profile = assertNotNullProfile(resolution)
        assertTrue("Enable MonoIcon before this single device check", ConfigManager.isEnabledFromUi())
        val directory = "/data/user_de/${android.os.Process.myUid() / 100000}/com.miui.home/files"
        val before = root("cat $directory/monoicon_palette.applied 2>/dev/null || true")
        // A new generation proves this run was acknowledged, even if a stale
        // .applied and status file survived a previous launcher process.
        HyperOs4PalettePublisher.invalidateExport()
        assertTrue("Profile/PNG/Palette publication failed", HyperOs4PalettePublisher.flushBeforeRestart(context))
        val modulePath = context.packageManager.getApplicationInfo(context.packageName,
            PackageManager.ApplicationInfoFlags.of(0)).sourceDir
        val output = File(context.cacheDir, "profile-device-check.txt")
        val script = """
            set -e
            attempts=0
            until cmp -s $directory/monoicon_palette.txt $directory/monoicon_palette.applied; do
                attempts=${'$'}((attempts + 1))
                [ "${'$'}attempts" -lt 20 ] || exit 2
                sleep 0.2
            done
            cat $directory/monoicon_palette.applied
            cat $directory/monoicon_hyos_palette.txt
            sha256sum $directory/monoicon_profiles/${profile.digest}.json
            pidof com.miui.home
        """.trimIndent()
        val process = ProcessBuilder("su", "-M", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("Root verification timed out", process.waitFor(10, TimeUnit.SECONDS))
            val result = output.readText()
            assertEquals(result, 0, process.exitValue())
            val config = result.lineSequence().first().trim().split(' ')
            assertEquals("3", config[1])
            assertEquals(profile.digest, config[8])
            assertNotEquals("This run must publish a new generation", before.trim(), result.lineSequence().first().trim())
            val status = result.lineSequence().first { it.startsWith("profile=") }
            val fields = Regex("(?:^| )(profile|digest|result|pid)=([^ ]+)").findAll(status)
                .associate { it.groupValues[1] to it.groupValues[2] }
            assertEquals(profile.id, fields["profile"])
            assertEquals(profile.digest, fields["digest"])
            assertEquals("1", fields["result"])
            val pid = result.trim().lineSequence().last().trim()
            assertTrue("One launcher process is required: $pid", Regex("[0-9]+").matches(pid))
            assertEquals("Status belongs to a stale launcher process", pid, fields["pid"])
            // Native images packaged inside an APK appear as APK-backed maps.
            val maps = root("cat /proc/$pid/maps")
            assertTrue("Current module APK is not mapped in the launcher", maps.lineSequence().any {
                it.endsWith(modulePath) && it.contains("r-xp")
            })
            assertTrue(result, result.lineSequence().any { it.startsWith(profile.digest + " ") })
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            output.delete()
        }
    }

    private fun assertNotNullProfile(resolution: ProfileRepository.Resolution) =
        requireNotNull(resolution.profile) { resolution.reason }

    private fun root(script: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.cacheDir, "profile-device-preflight.txt")
        val process = ProcessBuilder("su", "-M", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue("Root query timed out", process.waitFor(10, TimeUnit.SECONDS))
            val result = output.readText()
            assertEquals(result, 0, process.exitValue())
            return result
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            output.delete()
        }
    }
}
