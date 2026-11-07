package com.jerrey.monoicon

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

/**
 * Explicit opt-in: exercises app -> root -> launcher transport and restores settings.
 * Enabled captures restart the launcher; disabling waits for the product's own
 * restart. This does not verify refresh without restart.
 */
@RunWith(AndroidJUnit4::class)
class HyperOs4DeviceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val launcherFiles = "/data/user_de/${android.os.Process.myUid() / 100000}/com.miui.home/files"

    private fun root(command: String, timeoutSeconds: Long = 15): ByteArray {
        // Drain into files so a blocked read or a full stderr pipe cannot bypass
        // the process timeout. Preserve stdout bytes for PNG decoding.
        val output = File.createTempFile("hyperos4-root-", ".out", context.cacheDir)
        val error = File.createTempFile("hyperos4-root-", ".err", context.cacheDir)
        var process: Process? = null
        try {
            val child = ProcessBuilder("su", "-M", "-c", command)
                .redirectOutput(output).redirectError(error).start().also { process = it }
            assertTrue("root timed out", child.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            assertEquals("root failed: ${error.readText().take(2000)}", 0, child.exitValue())
            return output.readBytes()
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
            output.delete()
            error.delete()
        }
    }

    private fun commit(): List<String> {
        assertTrue("configuration must commit in the global namespace", HyperOs4PalettePublisher.flushBeforeRestart(context))
        val content = root("cat $launcherFiles/monoicon_palette.txt").toString(Charsets.UTF_8)
        root(acknowledgementScript(content) + """

            attempts=0
            until native_ready; do
                attempts=${'$'}((attempts + 1))
                if [ "${'$'}attempts" -ge 30 ]; then
                    echo 'Current launcher did not acknowledge the expected configuration' >&2
                    cat '${launcherFiles}/monoicon_hyos_palette.txt' >&2
                    exit 1
                fi
                sleep 0.2
            done
        """.trimIndent())
        return content.trim().split(Regex("\\s+"))
    }

    /** File equality alone could accept an acknowledgement left by a dead PID. */
    private fun acknowledgementScript(content: String, requireCleanDisabled: Boolean = false): String {
        val fields = content.trim().split(Regex("\\s+"))
        require(fields.size == 9 && fields[0] == "MONOICON" && fields[1] == "3")
        require(fields[2] in setOf("0", "1") && Regex("[a-f0-9]{64}").matches(fields[8]))
        require(Regex("g-[a-f0-9]{32}").matches(fields[7]))
        require(!requireCleanDisabled || fields[2] == "0")
        return """
            expected=${quote(content.trim())}
            current_pid() {
                candidate=${'$'}(pidof com.miui.home) || return 1
                case "${'$'}candidate" in ''|*[!0-9]*) return 1 ;; esac
                [ "${'$'}(tr '\000' '\n' < "/proc/${'$'}candidate/cmdline" | head -n 1)" = com.miui.home ] || return 1
                printf '%s' "${'$'}candidate"
            }
            native_ready() {
                current=${'$'}(current_pid) || return 1
                [ "${'$'}(cat '$launcherFiles/monoicon_palette.txt')" = "${'$'}expected" ] || return 1
                cmp -s '$launcherFiles/monoicon_palette.txt' '$launcherFiles/monoicon_palette.applied' || return 1
                awk -v wantedPid="${'$'}current" -v wantedDigest='${fields[8]}' -v wantedResult='${if (fields[2] == "1") "1" else "12"}' -v cleanDisabled=${if (requireCleanDisabled) 1 else 0} '
                    { for (i = 1; i <= NF; i++) {
                        split(${'$'}i, field, "=")
                        if (field[1] == "pid") process = field[2]
                        if (field[1] == "digest") digest = field[2]
                        if (field[1] == "result") result = field[2]
                        if (field[1] == "words_written") written = field[2]
                    } }
                    END { exit !(process == wantedPid && digest == wantedDigest && result == wantedResult && (!cleanDisabled || written == "0")) }
                ' '$launcherFiles/monoicon_hyos_palette.txt' || return 1
                if [ ${if (requireCleanDisabled) 1 else 0} -eq 1 ]; then
                    [ "${'$'}(cat '$launcherFiles/monoicon_refresh.applied')" = "${'$'}current|${fields[7]}|${fields[8]}" ] || return 1
                fi
                [ "${'$'}(current_pid)" = "${'$'}current" ]
            }
        """.trimIndent() + "\n"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun icon(config: List<String>, name: String): Bitmap {
        val bytes = root("cat $launcherFiles/monoicon_icons/${config[7]}/$name.png")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    }

    private fun restartLauncherAndCapture(name: String, restart: Boolean = true) {
        // These captures verify post-restart output only. Use the separate
        // acceptance driver and an unchanged PID to verify live color refresh.
        // HyperOS may freeze the test process as soon as HOME covers it. Keep
        // capture and bringing the app back in the same independent root child.
        val file = File(context.cacheDir, "hyperos4-$name.png")
        val content = root("cat $launcherFiles/monoicon_palette.txt").toString(Charsets.UTF_8)
        root(acknowledgementScript(content, requireCleanDisabled = !restart) + """

            return_to_app() {
                result=${'$'}?
                trap - EXIT
                set +e
                timeout 3 am start -n com.jerrey.monoicon/.MainActivity >/dev/null
                restored=${'$'}?
                [ "${'$'}result" -ne 0 ] || result=${'$'}restored
                exit "${'$'}result"
            }
            trap return_to_app EXIT
            set -e
            old=
            if [ ${if (restart) 1 else 0} -eq 1 ]; then
                old=${'$'}(current_pid)
                kill "${'$'}old"
            fi
            timeout 3 input keyevent KEYCODE_HOME
            attempts=0
            until native_ready && { [ -z "${'$'}old" ] || [ "${'$'}current" != "${'$'}old" ]; }; do
                attempts=${'$'}((attempts + 1))
                if [ "${'$'}attempts" -ge 40 ]; then
                    echo "Restarted launcher did not acknowledge configuration; previous PID=${'$'}old" >&2
                    cat '$launcherFiles/monoicon_hyos_palette.txt' >&2
                    exit 1
                fi
                sleep 0.2
            done
            captured=${'$'}current
            sleep 1
            if ! native_ready || [ "${'$'}current" != "${'$'}captured" ]; then
                echo 'Launcher changed before screenshot' >&2
                exit 1
            fi
            timeout 3 screencap -p ${quote(file.path)}
            chown ${android.os.Process.myUid()}:${android.os.Process.myUid()} ${quote(file.path)}
            if ! native_ready || [ "${'$'}current" != "${'$'}captured" ]; then
                echo 'Launcher changed during screenshot' >&2
                exit 1
            fi
        """.trimIndent(), timeoutSeconds = 25)
        assertTrue(file.length() > 8)
    }

    @Test fun realPublicationShapePaletteLawniconsAndDisableRoundTrip() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hyperos4Device") == "true")
        val bindingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (ConfigManager.xposedService() == null && System.nanoTime() < bindingDeadline) Thread.sleep(100)
        val service = ConfigManager.xposedService()
        assertNotNull("LSPosed preference service must bind before saving settings", service)
        assertTrue("Enable MonoIcon for com.miui.home in LSPosed before this device check",
            service!!.scope.contains("com.miui.home"))
        val enabled = ConfigManager.isEnabledFromUi()
        val circle = ConfigManager.isCircleIconsEnabledFromUi()
        val lawnicons = ConfigManager.isLawniconsEnabledFromUi()
        val variant = ConfigManager.getVariantIdFromUi()
        var failure: Throwable? = null
        try {
            ConfigManager.setEnabled(true)
            ConfigManager.setCircleIconsEnabled(true)
            ConfigManager.setLawniconsEnabled(true)
            ConfigManager.setVariantId("tonal_spot")
            val first = commit()
            val rounded = icon(first, "com.jerrey.monoicon.MainActivity")
            try {
                assertEquals("normal icon export width", 512, rounded.width)
                assertEquals("normal icon export height", 512, rounded.height)
                assertEquals(0, Color.alpha(rounded.getPixel(0, 0)))
                assertTrue("circle centre remains opaque",
                    Color.alpha(rounded.getPixel(rounded.width / 2, rounded.height / 2)) > 220)
            } finally {
                rounded.recycle()
            }
            restartLauncherAndCapture("circle")

            ConfigManager.setVariantId("expressive")
            ConfigManager.setLawniconsEnabled(false)
            val second = commit()
            assertNotEquals(first[7], second[7])
            assertNotEquals(first.subList(3, 5), second.subList(3, 5))
            assertEquals("0", second[6])
            restartLauncherAndCapture("expressive-aosp")

            ConfigManager.setCircleIconsEnabled(false)
            assertEquals("0", commit()[5])
            restartLauncherAndCapture("system-shape")

            ConfigManager.setEnabled(false)
            val disabled = commit()
            assertEquals("0", disabled[2])
            // HyperOs4IconRefresh already restarts a process that restored patched
            // instructions. Wait for its clean PID and refresh marker rather than
            // racing that restart with another kill from this test.
            restartLauncherAndCapture("disabled", restart = false)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            try {
                ConfigManager.setEnabled(enabled)
                ConfigManager.setCircleIconsEnabled(circle)
                ConfigManager.setLawniconsEnabled(lawnicons)
                ConfigManager.setVariantId(variant)
                // Save first, then restart: a stale/uninjected launcher must not
                // prevent the restored settings from reaching the next process.
                assertTrue("restored configuration must commit", HyperOs4PalettePublisher.flushBeforeRestart(context))
                restartLauncherAndCapture("restored")
                commit()
            } catch (restoreFailure: Throwable) {
                if (failure == null) failure = restoreFailure else failure.addSuppressed(restoreFailure)
            }
        }
        failure?.let { throw it }
    }
}
