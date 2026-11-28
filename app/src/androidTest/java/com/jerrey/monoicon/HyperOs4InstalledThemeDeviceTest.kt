package com.jerrey.monoicon

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.hook.HyperOs4Environment
import com.jerrey.monoicon.theme.archive.ThemeIconZip
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run after the ordinary icon theme has been applied. All root operations are read-only. */
@RunWith(AndroidJUnit4::class)
class HyperOs4InstalledThemeDeviceTest {
    @Test fun installedZipMatchesTheManagedJournalAndContainsLauncherIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires HyperOS 4 and the primary Android user", HyperOs4Environment.isSupported(context))
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "read-active-theme-").toFile()
        try {
            val output = File(work, "icons")
            val errors = File(work, "errors")
            assumeTrue("Requires granted root access", runCatching {
                readRoot("id -u", output, errors) == 0 && output.readText().trim() == "0"
            }.getOrDefault(false))

            val exit = readRoot($$"""
                set -eu
                problem() { echo "$*" >&2; exit 1; }
                theme=/data/system/theme
                target=$theme/icons
                state=/data/system/monoicon-theme
                journal=$state/state-v1
                [ -f "$target" ] && [ ! -L "$target" ] || problem "No installed icons ZIP"
                [ -f "$journal" ] && [ ! -L "$journal" ] || problem "No MonoIcon theme journal"
                [ "$(stat -c '%u:%a' "$state")" = '0:700' ] || problem "Unexpected state permissions"
                original=$(sed -n '1p' "$journal")
                live=$(sha256sum "$target" | cut -d' ' -f1)
                [ "$live" != "$original" ] && [ "$live" = "$(sed -n '2p' "$journal")" ] &&
                    [ "$live" = "$(sed -n '3p' "$journal")" ] || problem "The active archive is not committed"
                [ "$(sed -n '4p' "$journal")" = '0' ] || problem "Theme refresh is pending"
                if [ "$original" = '-' ]; then
                    reference=$theme
                    mode=644
                else
                    reference=$state/original.icons
                    [ "$original" = "$(sha256sum "$reference" | cut -d' ' -f1)" ] || problem "Unexpected original theme"
                    mode=$(stat -c '%a' "$reference")
                fi
                [ "$(stat -c '%u:%g' "$target")" = "$(stat -c '%u:%g' "$reference")" ] &&
                    [ "$(stat -c '%a' "$target")" = "$mode" ] || problem "Theme file ownership or mode differs"
                label=$(ls -Zd "$reference" | awk '{print $1}')
                [ -n "$label" ] && [ "$label" = "$(ls -Zd "$target" | awk '{print $1}')" ] || problem "Theme label differs"
                cat "$target"
            """.trimIndent(), output, errors)
            assertEquals(errors.readText(), 0, exit)
            assertTrue("Expected a MonoIcon-managed theme ZIP", ThemeIconZip.isManaged(output))
            ZipFile(output).use { zip ->
                val pkg = context.packageName
                val metadata = zip.getInputStream(zip.getEntry("META-INF/monoicon-theme-v1.tsv"))
                    .bufferedReader().use { it.readLines() }
                assertTrue("Missing installed package in ownership index", pkg in metadata)
                for (name in listOf("$pkg.png", "$pkg.MainActivity.png")) {
                    val path = "res/drawable-xxhdpi/$name"
                    val bytes = zip.getInputStream(checkNotNull(zip.getEntry(path)) { "Missing $path" })
                        .use { it.readBytes() }
                    val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                    assertTrue("Wrong owner or image digest for $path", "$pkg\t$path\t$digest" in metadata)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    assertEquals(192, options.outWidth)
                    assertEquals(192, options.outHeight)
                }
            }
        } finally { work.deleteRecursively() }
    }

    private fun readRoot(command: String, output: File, errors: File): Int {
        val child = ProcessBuilder("su", "-M", "-c", command).redirectOutput(output).redirectError(errors).start()
        try {
            child.outputStream.close()
            check(child.waitFor(45, TimeUnit.SECONDS)) { "Timed out reading the active system theme" }
            return child.exitValue()
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }
}
