package com.jerrey.monoicon.hook

import com.jerrey.monoicon.config.EffectiveIconStyle
import com.jerrey.monoicon.config.IconSourceStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64

class HyperOs4ThemeRenderTest {
    @get:Rule val temp = TemporaryFolder()
    private val black = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGNgAAAAAgABSK+kcQAAAABJRU5ErkJggg==")
    private val white = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAAAAAA6fptVAAAACklEQVR4nGP4DwABAQEAsTj2FAAAAABJRU5ErkJggg==")

    @Test fun packageCacheChangesWithStylePaletteAndBaseOrSplitUpdates() {
        val style = EffectiveIconStyle(IconSourceStyle.NATIVE, 50, 10, 100)
        fun stamp(environment: String = "light", appearance: EffectiveIconStyle = style, version: Long = 1L,
                  updated: Long = 100L, base: String = "/base.apk", splits: Array<String>? = null) =
            HyperOs4ThemeRenderer.apkStamp(environment, appearance, version, updated, base, splits)
        val initial = stamp()
        assertEquals(initial, stamp(splits = emptyArray()))
        assertNotEquals(initial, stamp("other-wallpaper"))
        assertNotEquals(initial, stamp(appearance = style.copy(style = IconSourceStyle.GENERATED)))
        assertNotEquals(initial, stamp(updated = 101L))
        assertNotEquals(initial, stamp(version = 2L))
        assertNotEquals(initial, stamp(base = "/reinstalled/base.apk"))
        assertNotEquals(initial, stamp(splits = arrayOf("/feature.apk")))
    }

    @Test fun activityEntriesPreserveCaseAndThePrimaryLaunchAlias() {
        val pkg = "com.MobileTicket"
        val main = File("main")
        val other = File("second")
        val foreign = File("foreign")
        val icons = ThemeIconEntries.entries(pkg, ".Main", linkedMapOf(
            ".Secondary" to other, ".Main" to main, "com.MobileTicketing.External" to foreign))
        val prefix = "res/drawable-xxhdpi/$pkg"
        assertEquals(mapOf("$prefix.png" to main, "$prefix.Main.png" to main,
            "$prefix.Secondary.png" to other, "$prefix#com.MobileTicketing.External.png" to foreign), icons)
        assertTrue(ThemeIconEntries.entries(pkg, ".Removed", emptyMap()).isEmpty())
        assertEquals(other, ThemeIconEntries.entries(pkg, ".Removed", mapOf(".Secondary" to other))["$prefix.png"])
    }

    @Test fun sharesCachedRendersAcrossBatchesAndReplacesOnlyTheRequestedPackage() {
        val cacheDirectory = temp.newFolder()
        val first = ThemePngCache(cacheDirectory, temp.newFolder())
        val previous = first.png("com.example", "component", false) { it.writeBytes(black); true }!!
        first.png("com.example.extra", "other", false) { it.writeBytes(black); true }
        val next = ThemePngCache(cacheDirectory, temp.newFolder())
        val hit = next.png("com.example", "component", false) { error("cache hit rendered again") }
        assertArrayEquals(black, hit!!.readBytes())
        val forced = ThemePngCache(cacheDirectory, temp.newFolder()).png("com.example", "component", true) {
            it.writeBytes(white)
            true
        }
        assertArrayEquals(white, forced!!.readBytes())
        assertArrayEquals(black, previous.readBytes())
        first.retain("com.example", emptySet())
        assertFalse(File(cacheDirectory, "com.example").exists())
        assertTrue(File(cacheDirectory, "com.example.extra/other.png").isFile)
        assertArrayEquals(white, forced.readBytes())
    }

    @Test fun failedReplacementLeavesThePreviousCompletePng() {
        val root = temp.newFolder()
        val first = ThemePngCache(root, temp.newFolder())
        first.png("com.example", "component", false) { it.writeBytes(black); true }
        val next = ThemePngCache(root, temp.newFolder())
        try {
            next.png("com.example", "component", true) {
                it.writeText("partial")
                throw IOException("render interrupted")
            }
            error("expected render failure")
        } catch (_: IOException) {}
        val result = next.png("com.example", "component", false) { error("previous cache lost") }
        assertArrayEquals(black, result!!.readBytes())
    }

    @Test fun truncatedCachedImageIsRenderedAgain() {
        val root = temp.newFolder()
        val cache = ThemePngCache(root, temp.newFolder())
        val dir = File(root, "com.example")
        assertTrue(dir.mkdir())
        File(dir, "component.png").writeBytes(black.copyOf(black.size - 4))
        val image = cache.png("com.example", "component", false) { it.writeBytes(white); true }
        assertArrayEquals(white, image!!.readBytes())
    }
}
