package com.jerrey.monoicon.theme.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.EOFException
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

class LargeIconPatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder("theme").canonicalFile }
    private val state by lazy { File(temporary.root.canonicalFile, "store") }
    private val first = "com.example/2x1/res/drawable-nxhdpi/com.example.png"
    private val second = "com.example/1x2/res/drawable-xxhdpi/com.example.png"

    @Test fun transfersCompletePatchesAndRestoresAnIconThatLeavesTheSelection() {
        val originalFirst = resource(first, 4, 2, 1).readBytes()
        val originalSecond = resource(second, 2, 4, 2).readBytes()
        val replacementFirst = rendered(4, 2, 3)
        val replacementSecond = rendered(2, 4, 4)
        val catalog = catalog()
        val packet = packet(catalog, first to replacementFirst, second to replacementSecond)
        assertEquals(catalog.first(), LargeIconPatch.parse(LargeIconPatch.describe(catalog.first())))
        apply(packet, catalog) {
            assertArrayEquals(originalFirst, File(root, first).readBytes())
            assertArrayEquals(originalSecond, File(root, second).readBytes())
        }
        assertArrayEquals(replacementFirst.readBytes(), File(root, first).readBytes())
        assertArrayEquals(replacementSecond.readBytes(), File(root, second).readBytes())

        val updated = catalog()
        apply(packet(updated, second to replacementSecond), updated)
        assertArrayEquals(originalFirst, File(root, first).readBytes())
        assertArrayEquals(replacementSecond.readBytes(), File(root, second).readBytes())
        assertTrue(store().restoreExcept(emptySet()))
        assertArrayEquals(originalSecond, File(root, second).readBytes())
    }

    @Test fun rejectsInvalidPathsAndImagesBeforePublishingAnyValidatedEntry() {
        val originalFirst = resource(first, 4, 2, 1).readBytes()
        val originalSecond = resource(second, 2, 4, 2).readBytes()
        val catalog = catalog()
        val replacement = rendered(4, 2, 3)
        for ((path, image) in listOf(
            "../outside.png" to rendered(2, 4, 4),
            "com.example/2x2/res/drawable-nxhdpi/com.example.png" to rendered(2, 4, 4),
            second to rendered(4, 2, 4),
            second to temporary.newFile().apply { writeText("invalid PNG") },
        )) {
            assertThrows(IllegalStateException::class.java) {
                apply(packet(catalog, first to replacement, path to image), catalog) { error("Commit started") }
            }
            assertArrayEquals(originalFirst, File(root, first).readBytes())
            assertArrayEquals(originalSecond, File(root, second).readBytes())
            assertFalse(state.exists())
        }
    }

    @Test fun rejectsACatalogMismatchAndTruncatedOrExtraPacketBytes() {
        val original = resource(first, 4, 2, 1).readBytes()
        resource(second, 2, 4, 2)
        val catalog = catalog()
        val packet = packet(catalog, first to rendered(4, 2, 3))

        assertThrows(IllegalStateException::class.java) {
            apply(packet, listOf(catalog.first().copy(sha = "0".repeat(64)), catalog.last()))
        }
        assertThrows(EOFException::class.java) { apply(packet.copyOf(packet.size - 1), catalog) }
        assertThrows(IllegalArgumentException::class.java) { apply(packet + byteArrayOf(0), catalog) }
        assertThrows(IOException::class.java) { apply(packet, catalog) { throw IOException("Catalog changed") } }
        assertArrayEquals(original, File(root, first).readBytes())
        assertFalse(state.exists())
    }

    @Test fun resumesAnInterruptedBatchWithoutLosingEitherOriginal() {
        val originalFirst = resource(first, 4, 2, 1).readBytes()
        val originalSecond = resource(second, 2, 4, 2).readBytes()
        val replacementFirst = rendered(4, 2, 3)
        val replacementSecond = rendered(2, 4, 4)
        val interrupted = store { directory ->
            if (directory == File(root, first).parentFile) throw IOException("Interrupted after first rename")
        }
        val catalog = catalog()
        val images = arrayOf(first to replacementFirst, second to replacementSecond)
        assertThrows(IOException::class.java) { apply(packet(catalog, *images), catalog, interrupted) }
        assertArrayEquals(replacementFirst.readBytes(), File(root, first).readBytes())
        assertArrayEquals(originalSecond, File(root, second).readBytes())
        assertTrue(store().needsRefresh())

        val resumed = catalog()
        apply(packet(resumed, *images), resumed)
        assertArrayEquals(replacementSecond.readBytes(), File(root, second).readBytes())
        assertTrue(store().restoreExcept(emptySet()))
        assertArrayEquals(originalFirst, File(root, first).readBytes())
        assertArrayEquals(originalSecond, File(root, second).readBytes())
    }

    private fun catalog() = listOf(first, second).map { path ->
        val file = File(root, path)
        val (width, height) = checkNotNull(LargeIconResources.dimensions(file))
        LargeIconResources.Asset(path, width, height, LargeIconResources.digest(file))
    }

    private fun packet(catalog: List<LargeIconResources.Asset>, vararg images: Pair<String, File>): ByteArray {
        val patch = temporary.newFile()
        LargeIconPatch.write(patch, catalog, linkedMapOf(*images))
        return patch.readBytes()
    }

    private fun apply(packet: ByteArray, catalog: List<LargeIconResources.Asset>,
                      fileStore: LargeIconFileStore = store(), beforeCommit: () -> Unit = {}) {
        val work = temporary.newFolder()
        LargeIconPatch.apply(packet.inputStream(), catalog, fileStore, work, beforeCommit)
    }

    private fun store(sync: (File) -> Unit = {}) = LargeIconFileStore(root, state, { _, _ -> }, sync)

    private fun resource(path: String, width: Int, height: Int, color: Int) = File(root, path).also {
        checkNotNull(it.parentFile).mkdirs()
        png(it, width, height, color)
    }

    private fun rendered(width: Int, height: Int, color: Int) = temporary.newFile().also { png(it, width, height, color) }

    private fun png(file: File, width: Int, height: Int, color: Int) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, (0xff shl 24) + color)
        check(ImageIO.write(image, "png", file))
    }
}
