package com.jerrey.monoicon

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.theme.archive.retireLegacyLauncherIcons
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyLauncherIconsInstrumentedTest {
    @Test fun retiresActiveMarkersInBothStorageLocationsAndPreservesOtherData() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(cache.toPath(), "launcher-migration").toFile()
        try {
            val device = File(root, "device").apply { mkdir() }
            val credential = File(root, "credential").apply { mkdir() }
            val active = File(device, "monoicon_large_icons.active").apply { writeText("active") }
            val record = "MONOICON 3 1 0 1 0 1 g-test digest"
            val palettes = listOf(device, credential).map { File(it, "monoicon_palette.txt").apply { writeText(record) } }
            val safety = File(device, "monoicon_safety_state.txt").apply { writeText("preserve") }

            repeat(2) { retireLegacyLauncherIcons(device, credential) }
            assertFalse(active.exists())
            palettes.forEach { assertEquals(record.replace("3 1 ", "3 0 "), it.readText()) }
            assertEquals("preserve", safety.readText())

            palettes.forEach { it.delete() }
            val symlinkTarget = File(root, "symlink-target").apply { writeText(record) }
            val hardlinkTarget = File(root, "hardlink-target").apply { writeText(record) }
            Os.symlink(symlinkTarget.path, palettes.first().path)
            try {
                Os.link(hardlinkTarget.path, palettes.last().path)
            } catch (e: ErrnoException) {
                if (e.errno != OsConstants.EACCES && e.errno != OsConstants.EPERM) throw e
                createFixtureHardLink(cache, root)
            }
            assertTrue(Files.isSameFile(hardlinkTarget.toPath(), palettes.last().toPath()))
            retireLegacyLauncherIcons(device, credential)
            assertEquals(record, symlinkTarget.readText())
            assertEquals(record, hardlinkTarget.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    // Some HyperOS builds deny link(2) to the app domain even within its private cache.
    private fun createFixtureHardLink(cache: File, root: File) {
        require(root.parentFile == cache && root.canonicalFile == root && root.name.startsWith("launcher-migration"))
        require(File(root, "hardlink-target").canonicalFile == File(root, "hardlink-target"))
        require(File(root, "credential").canonicalFile == File(root, "credential"))
        val directory = "'" + root.path.replace("'", "'\\''") + "'"
        val command = "cd $directory && /system/bin/ln hardlink-target credential/monoicon_palette.txt"
        val child = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        try {
            child.outputStream.close()
            check(child.waitFor(15, TimeUnit.SECONDS)) { "Timed out creating the fixture hard link" }
            val output = child.inputStream.bufferedReader().use { it.readText() }.trim()
            check(child.exitValue() == 0) { "Could not create the fixture hard link: $output" }
        } finally {
            if (child.isAlive) child.destroyForcibly()
            child.inputStream.close()
        }
    }
}
