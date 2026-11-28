package com.jerrey.monoicon.theme.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class LargeIconFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val root by lazy { temporary.newFolder("live").canonicalFile }
    private val state by lazy { File(temporary.root.canonicalFile, "state") }
    private val horizontal = "com.example/2x1/res/drawable-nxhdpi/com.example.png"
    private val vertical = "com.example/1x2/res/drawable-xxhdpi/com.example.png"

    @Test fun installsUpdatesAndRestoresOnlyInactiveResources() {
        val first = resource(horizontal, "first original")
        val second = resource(vertical, "second original")
        val replacements = store()
        assertTrue(replacements.replace(horizontal, digest(first), rendered("first edit")))
        assertTrue(replacements.replace(vertical, digest(second), rendered("second edit")))
        assertTrue(replacements.replace(horizontal, digest(first), rendered("new first edit")))
        assertFalse(replacements.replace(horizontal, digest(first), rendered("new first edit")))
        assertTrue(replacements.needsRefresh())
        replacements.markRefreshed()
        assertFalse(replacements.needsRefresh())

        assertTrue(store().restoreExcept(setOf(horizontal)))
        assertEquals("new first edit", first.readText())
        assertEquals("second original", second.readText())
        assertTrue(store().restoreExcept(emptySet()))
        assertEquals("first original", first.readText())
        assertFalse(store().restoreExcept(emptySet()))
    }

    @Test fun neverReinstatesDeletedOrReplacedThemeFiles() {
        val first = resource(horizontal, "first original")
        val second = resource(vertical, "second original")
        assertTrue(store().replace(horizontal, digest(first), rendered("first edit")))
        assertTrue(store().replace(vertical, digest(second), rendered("second edit")))
        val expected = digest(first)

        // Even equal bytes belong to the new theme when they came from a different inode.
        switch(first, "first edit")
        Files.delete(second.toPath())

        assertFalse(store().restoreExcept(emptySet()))
        assertEquals("first edit", first.readText())
        assertFalse(second.exists())
        assertFalse(store().replace(vertical, expected, rendered("deleted")))

        switch(first, "new theme")
        assertFalse(store().replace(horizontal, expected, rendered("stale render")))
        assertTrue(store().replace(horizontal, digest(first), rendered("new theme edit")))
        assertTrue(store().restoreExcept(emptySet()))
        assertEquals("new theme", first.readText())
    }

    @Test fun adoptsAnExternallyChangedFileAsTheNextOriginal() {
        val live = resource(horizontal, "original")
        assertTrue(store().replace(horizontal, digest(live), rendered("rendered")))
        live.writeText("new theme")
        assertTrue(store().replace(horizontal, digest(live), rendered("new render")))
        assertTrue(store().restoreExcept(emptySet()))
        assertEquals("new theme", live.readText())
    }

    @Test fun checksForExternalChangeAfterStagingAndBeforePublication() {
        val live = resource(horizontal, "original")
        val expected = digest(live)
        val replacements = store(sync = { directory ->
            if (directory == state && File(state, "refresh-pending").exists() && live.readText() == "original") {
                switch(live, "external switch")
            }
        })

        assertFalse(replacements.replace(horizontal, expected, rendered("rendered")))
        assertEquals("external switch", live.readText())
        assertFalse(store().restoreExcept(emptySet()))
        assertEquals("external switch", live.readText())
    }

    @Test fun restoresAfterAnInterruptedReplacementAndKeepsTheRefreshPending() {
        val live = resource(horizontal, "original")
        assertTrue(store().replace(horizontal, digest(live), rendered("first replacement")))
        store().markRefreshed()

        val interrupted = store(sync = { directory ->
            if (directory == live.parentFile && live.readText() == "second replacement") {
                throw IOException("interrupted after rename")
            }
        })
        val next = rendered("second replacement")
        assertThrows(IOException::class.java) { interrupted.replace(horizontal, digest(live), next) }
        assertEquals("second replacement", live.readText())
        assertTrue(store().needsRefresh())
        assertTrue(store().restoreExcept(emptySet()))
        assertEquals("original", live.readText())
    }

    @Test fun restoresThePreviousInstalledFileWhenTheNextRenameNeverHappened() {
        val live = resource(horizontal, "original")
        assertTrue(store().replace(horizontal, digest(live), rendered("first replacement")))
        store().markRefreshed()

        val interrupted = store(sync = { directory ->
            val owned = directory.list().orEmpty().count { it.startsWith("owned-") }
            if (owned == 2) throw IOException("interrupted before rename")
        })
        val next = rendered("second replacement")
        assertThrows(IOException::class.java) { interrupted.replace(horizontal, digest(live), next) }
        assertEquals("first replacement", live.readText())
        assertFalse(store().needsRefresh())
        assertTrue(store().restoreExcept(emptySet()))
        assertEquals("original", live.readText())
    }

    @Test fun anInterruptedRestoreStillRetriesTheRefresh() {
        val live = resource(horizontal, "original")
        assertTrue(store().replace(horizontal, digest(live), rendered("edited")))
        store().markRefreshed()

        val interrupted = store(sync = { directory ->
            if (directory == live.parentFile && live.readText() == "original") throw IOException("interrupted")
        })
        assertThrows(IOException::class.java) { interrupted.restoreExcept(emptySet()) }
        assertEquals("original", live.readText())
        assertFalse(store().restoreExcept(emptySet()))
        assertTrue(store().needsRefresh())
        store().markRefreshed()
        assertFalse(store().needsRefresh())
    }

    @Test fun stagesEachWriteUsingTheOriginalFileAttributes() {
        val live = resource(horizontal, "original")
        val seen = mutableListOf<Pair<String, String>>()
        val replacements = store(preserve = { reference, staged -> seen.add(reference.readText() to staged.readText()) })

        assertTrue(replacements.replace(horizontal, digest(live), rendered("edit")))
        assertTrue(replacements.restoreExcept(emptySet()))
        assertEquals(listOf("original" to "original", "original" to "edit", "original" to "original"), seen)
    }

    private fun store(preserve: (File, File) -> Unit = { _, _ -> }, sync: (File) -> Unit = {}) =
        LargeIconFileStore(root, state, preserve, sync)

    private fun resource(relative: String, contents: String) = File(root, relative).apply {
        parentFile?.mkdirs()
        writeText(contents)
    }

    private fun switch(target: File, contents: String) {
        Files.move(rendered(contents).toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun rendered(contents: String) = temporary.newFile().apply { writeText(contents) }

    private fun digest(file: File) = LargeIconResources.digest(file)
}
