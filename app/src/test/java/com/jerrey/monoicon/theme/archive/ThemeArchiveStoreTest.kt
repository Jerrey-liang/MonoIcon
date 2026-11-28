package com.jerrey.monoicon.theme.archive

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ThemeArchiveStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val theme by lazy { temporary.newFolder("theme") }
    private val state by lazy { temporary.newFolder("state") }
    private val live get() = File(theme, "icons")
    private val draw = "res/drawable-xxhdpi/"
    private val parent = "com.Example"
    private val child = "com.Example.pro"

    private fun store(sync: (File) -> Unit = {}, refresh: () -> Boolean = { true }) =
        ThemeArchiveStore(live, state, { _, _ -> }, sync, refresh)

    private fun png(value: Int) = temporary.newFile().apply {
        DataOutputStream(outputStream()).use { out ->
            fun chunk(type: String, data: ByteArray) {
                val body = type.toByteArray() + data
                out.writeInt(data.size)
                out.write(body)
                out.writeInt(CRC32().also { it.update(body) }.value.toInt())
            }
            out.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            chunk("IHDR", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0))
            val pixel = ByteArrayOutputStream()
            DeflaterOutputStream(pixel).use { it.write(byteArrayOf(0, value.toByte())) }
            chunk("IDAT", pixel.toByteArray())
            chunk("IEND", byteArrayOf())
        }
    }

    private fun patch(packages: Map<String, Map<String, File>>) =
        temporary.newFile().also { ThemeIconZip.writePatch(it, packages) }

    private fun one(pkg: String, icon: File) = patch(mapOf(pkg to mapOf("$draw$pkg.png" to icon)))

    private fun original(vararg entries: Pair<String, String>): ByteArray {
        ZipOutputStream(live.outputStream()).use { zip ->
            zip.setComment("Existing theme")
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return live.readBytes()
    }

    private fun content(name: String): ByteArray = ZipFile(live).use { zip ->
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    }

    @Test fun partialUpdatesUseExactOwnersAndRestoreOriginalEntries() {
        val parentName = "$draw$parent.png"
        val childName = "$draw$child.png"
        val component = "$draw$parent#outside.Launcher.png"
        val config = "transform_config.xml"
        val original = original(parentName to "stock", config to "existing mapping", "fancy_icons/clock/manifest.xml" to "clock")
        val a = png(1)
        val b = png(2)
        val s = store()
        val initial = patch(mapOf(parent to mapOf(parentName to a, component to a), child to mapOf(childName to b)))
        assertTrue(s.apply(initial, true).changed)
        assertEquals(ThemeArchiveStatus.ACTIVE, s.status())
        assertFalse(s.apply(initial, true).changed)
        assertEquals("existing mapping", content(config).decodeToString())
        assertArrayEquals(a.readBytes(), content(component))
        assertTrue(s.apply(patch(mapOf(parent to emptyMap())), false).changed)
        assertEquals("stock", content(parentName).decodeToString())
        assertArrayEquals(b.readBytes(), content(childName))
        assertNull(ZipFile(live).use { it.getEntry(component) })
        assertEquals("clock", content("fancy_icons/clock/manifest.xml").decodeToString())
        assertTrue(s.apply(patch(emptyMap()), true).changed)
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
        assertArrayEquals(original, live.readBytes())
    }

    @Test fun stockThemeWithoutIconsAndFailedRefreshCanBeRetried() {
        var refreshed = false
        val s = store { refreshed }
        val update = one(parent, png(1))
        assertEquals(ThemeArchiveResult(true, false), s.apply(update, true))
        refreshed = true
        assertEquals(ThemeArchiveResult(false, true), s.apply(update, true))
        refreshed = false
        assertEquals(ThemeArchiveResult(true, false), s.restore())
        assertFalse(live.exists())
        refreshed = true
        assertEquals(ThemeArchiveResult(false, true), s.restore())
        assertFalse(File(state, "state-v1").exists())
    }

    @Test fun disableAfterSystemRestoresStockAbsenceReleasesOnlyManagement() {
        val s = store()
        s.apply(one(parent, png(1)), true)
        assertTrue(live.delete())
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
        assertThrows(ThemeArchiveConflictException::class.java) { s.apply(one(parent, png(2)), true) }
        val withoutRefresh = store { error("Already at baseline") }
        assertEquals(ThemeArchiveResult(false, true), withoutRefresh.restore())
        assertFalse(live.exists())
        assertFalse(File(state, "state-v1").exists())
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
    }

    @Test fun disableAfterUserRestoresOriginalBytesPreservesTheExistingInode() {
        val baseline = original("settings.xml" to "stock")
        val s = store()
        s.apply(one(parent, png(1)), true)
        live.writeBytes(baseline)
        val timestamp = 1_600_000_000_000
        assertTrue(live.setLastModified(timestamp))
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
        assertEquals(ThemeArchiveResult(false, true), store { error("Already at baseline") }.restore())
        assertEquals(timestamp, live.lastModified())
        assertArrayEquals(baseline, live.readBytes())
        assertFalse(File(state, "state-v1").exists())
    }

    @Test fun packageAndActivitySharingANameSurviveIncrementalChanges() {
        val name = "$draw$child.png"
        val parentActivity = png(1)
        val updatedActivity = png(2)
        val childApp = png(3)
        val s = store()
        val initial = patch(mapOf(parent to mapOf(name to parentActivity), child to mapOf(name to childApp)))
        s.apply(initial, true)
        assertArrayEquals(childApp.readBytes(), content(name))
        s.apply(patch(mapOf(parent to mapOf(name to updatedActivity))), false)
        assertArrayEquals(childApp.readBytes(), content(name))
        s.apply(patch(mapOf(child to emptyMap())), false)
        assertArrayEquals(updatedActivity.readBytes(), content(name))
        s.apply(one(child, childApp), false)
        assertArrayEquals(childApp.readBytes(), content(name))
    }

    @Test fun externalThemeRequiresExplicitAdoptionAndBecomesNewRestoreTarget() {
        val old = original("old" to "original")
        val update = one(parent, png(1))
        val s = store()
        s.apply(update, true)
        val external = original("new" to "selected theme")
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
        assertFalse(s.canRecoverMissingOriginal())
        assertThrows(ThemeArchiveConflictException::class.java) { s.recoverMissingOriginal() }
        assertThrows(ThemeArchiveConflictException::class.java) { s.restore() }
        assertThrows(ThemeArchiveConflictException::class.java) { s.apply(update, true) }
        assertArrayEquals(external, live.readBytes())
        s.acceptCurrentTheme()
        assertArrayEquals(old, File(state, "previous-original.icons").readBytes())
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
        s.apply(update, true)
        assertEquals("selected theme", content("new").decodeToString())
        s.restore()
        assertArrayEquals(external, live.readBytes())
    }

    @Test fun deletingManagedArchiveAllowsExplicitRecoveryOfVerifiedOriginalOnly() {
        val stock = original("assets.xml" to "my original theme")
        val update = one(parent, png(1))
        val s = store()
        s.apply(update, true)
        assertTrue(live.delete())
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
        assertThrows(ThemeArchiveConflictException::class.java) { s.restore() }
        assertTrue(s.canRecoverMissingOriginal())
        val backup = File(state, "original.icons")
        backup.writeText("truncated")
        assertFalse(s.canRecoverMissingOriginal())
        assertThrows(ThemeArchiveConflictException::class.java) { s.recoverMissingOriginal() }
        assertFalse(live.exists())
        backup.writeBytes(stock)
        assertEquals(ThemeArchiveResult(true, true), s.recoverMissingOriginal())
        assertArrayEquals(stock, live.readBytes())
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
    }

    @Test fun themeServiceRemovalDuringNotificationIsReportedAsConflict() {
        val stock = original("assets.xml" to "original")
        val s = store { live.delete() }
        assertThrows(ThemeArchiveConflictException::class.java) { s.apply(one(parent, png(1)), true) }
        assertTrue(s.canRecoverMissingOriginal())
        store().recoverMissingOriginal()
        assertArrayEquals(stock, live.readBytes())
    }

    @Test fun newThemeWinsIfItArrivesDuringExplicitRecovery() {
        original("assets.xml" to "previous theme")
        val s = store()
        s.apply(one(parent, png(1)), true)
        assertTrue(live.delete())
        var selected = byteArrayOf()
        val race = store(sync = { dir ->
            if (dir == state && !live.exists()) selected = original("selected" to "newer theme")
        })
        assertThrows(ThemeArchiveConflictException::class.java) { race.recoverMissingOriginal() }
        assertArrayEquals(selected, live.readBytes())
        assertFalse(s.canRecoverMissingOriginal())
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
    }

    @Test fun externalThemeCanBeAdoptedEvenIfFormerBackupIsDamaged() {
        original("original.xml" to "old selection")
        val s = store()
        s.apply(one(parent, png(1)), true)
        File(state, "original.icons").writeText("damaged backup")
        val selected = original("new.xml" to "new selection")
        s.acceptCurrentTheme()
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
        assertArrayEquals(selected, live.readBytes())
        assertEquals("damaged backup", File(state, "previous-damaged.icons").readText())
    }

    @Test fun truncatedPngFailsBeforeAffectingActiveTheme() {
        val broken = png(1)
        val bytes = broken.readBytes()
        broken.writeBytes(bytes.copyOf(bytes.size - 6))
        assertFalse(ThemeIconZip.isRenderedPng(broken))
        assertThrows(IllegalArgumentException::class.java) { one(parent, broken) }
    }

    @Test fun interruptedRenameReconcilesThroughDigest() {
        val stock = original("asset.xml" to "original")
        val update = one(parent, png(1))
        // The target has been renamed, but its completed record has not yet been written.
        assertThrows(IOException::class.java) {
            store(sync = { if (it == theme) throw IOException("simulated interruption") }).apply(update, true)
        }
        assertEquals(ThemeArchiveStatus.ACTIVE, store().status())
        assertTrue(store().restore().changed)
        assertArrayEquals(stock, live.readBytes())
    }

    @Test fun interruptedBeforeFirstRenameLeavesOriginalUnmanaged() {
        val stock = original("asset.xml" to "original")
        val update = one(parent, png(1))
        assertThrows(IOException::class.java) {
            store(sync = {
                if (it == state && File(state, "state-v1").exists()) throw IOException("simulated interruption")
            }).apply(update, true)
        }
        assertEquals(ThemeArchiveStatus.INACTIVE, store().status())
        assertArrayEquals(stock, live.readBytes())
    }

    @Test fun missingOrCorruptBackupCannotOverwriteActiveArchive() {
        original("data.xml" to "original")
        val s = store()
        s.apply(one(parent, png(1)), true)
        val activeBytes = live.readBytes()
        File(state, "original.icons").writeText("damaged")
        assertThrows(IOException::class.java) { s.restore() }
        assertArrayEquals(activeBytes, live.readBytes())
        assertTrue(File(state, "state-v1").delete())
        assertEquals(ThemeArchiveStatus.CONFLICT, s.status())
        assertThrows(ThemeArchiveConflictException::class.java) { s.acceptCurrentTheme() }
    }

    @Test fun unsafeArchivePathsAndSignedThemesFailBeforeAnyChange() {
        val s = store()
        val update = one(parent, png(1))
        for (entry in listOf("../other", "META-INF/SIGNATURE.RSA")) {
            val stock = original(entry to "preserve")
            assertThrows(IllegalArgumentException::class.java) { s.apply(update, true) }
            assertArrayEquals(stock, live.readBytes())
            assertFalse(File(state, "state-v1").exists())
        }
        assertThrows(IllegalArgumentException::class.java) {
            patch(mapOf(parent to mapOf("$draw$parent/../../icons.png" to png(2))))
        }
    }

    @Test fun metadataFailurePreservesOriginal() {
        val stock = original("data.xml" to "original")
        val s = ThemeArchiveStore(live, state, { _, _ -> throw IOException("label unavailable") }, {}, { true })
        assertThrows(IOException::class.java) { s.apply(one(parent, png(1)), true) }
        assertArrayEquals(stock, live.readBytes())
        assertEquals(ThemeArchiveStatus.INACTIVE, s.status())
    }

    @Test fun stagedAndAbandonedFilesStayInsidePrivateDirectory() {
        original("stock.xml" to "original")
        val s = ThemeArchiveStore(live, state, { _, staged ->
            assertEquals(state, staged.parentFile)
            assertEquals(listOf("icons"), theme.list()?.toList())
        }, {}, { true })
        s.apply(one(parent, png(1)), true)
        val abandoned = File(state, "stage-12345.tmp").apply { writeText("interrupted") }
        val unrelated = File(state, "other-12345.tmp").apply { writeText("keep") }
        val external = File(theme, "stage-12345.tmp").apply { writeText("user file") }
        s.cleanInterruptedWork()
        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        assertTrue(external.exists())
        assertEquals(ThemeArchiveStatus.ACTIVE, s.status())
    }
}
