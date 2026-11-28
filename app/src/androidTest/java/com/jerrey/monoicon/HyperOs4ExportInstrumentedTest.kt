package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.jerrey.monoicon.color.IconDrawableCache
import com.jerrey.monoicon.hook.HyperOs4IconExporter
import com.jerrey.monoicon.hook.HyperOs4LargeIconInputs
import com.jerrey.monoicon.hook.HyperOs4LargeIconGeometry
import com.jerrey.monoicon.hook.HyperOs4ThemeRenderer
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HyperOs4ExportInstrumentedTest {
    @Test fun rendersInstalledAppAndReportsRemovedPackageWithoutProfile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hyperos4-test-system-icons")
        try {
            val missing = "com.jerrey.monoicon.uninstalled"
            val icons = HyperOs4ThemeRenderer.render(context, directory, setOf(context.packageName, missing))!!
            assertTrue(icons.getValue(missing).isEmpty())
            val entries = icons.getValue(context.packageName)
            val prefix = "res/drawable-xxhdpi/" + context.packageName
            assertEquals(entries.getValue("$prefix.MainActivity.png"), entries.getValue("$prefix.png"))
            val bitmap = BitmapFactory.decodeFile(entries.getValue("$prefix.png").path)
            try {
                assertEquals(192, bitmap.width)
                assertEquals(192, bitmap.height)
            } finally { bitmap.recycle() }
            val identity = "${context.packageName}/${context.packageName}.MainActivity"
            val previous = IconDrawableCache.get(identity)
            try {
                IconDrawableCache.put(identity, ColorDrawable(Color.MAGENTA))
                val rebuilt = HyperOs4ThemeRenderer.render(context, directory, setOf(context.packageName))!!
                assertTrue(entries.getValue("$prefix.png").readBytes().contentEquals(
                    rebuilt.getValue(context.packageName).getValue("$prefix.png").readBytes()))
            } finally {
                if (previous == null) IconDrawableCache.remove(identity) else IconDrawableCache.put(identity, previous)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun opaqueTonalArtworkUsesItsVisibleContrast() {
        val bitmap = Bitmap.createBitmap(intArrayOf(Color.WHITE, Color.BLACK), 2, 1, Bitmap.Config.ARGB_8888)
        try {
            assertTrue(AospMonochromeMaskStrategy.hasUsableGlyph(bitmap, tonal = true))
            assertTrue(!AospMonochromeMaskStrategy.hasUsableGlyph(bitmap))
        } finally { bitmap.recycle() }
    }

    @Test fun profileExporterProducesOnlyConfiguredLargeIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hyperos4-test-export")
        try {
            val geometry = HyperOs4LargeIconGeometry.Geometry(100.0, 150.0, 70.0)
            val inputs = HyperOs4LargeIconInputs(geometry, listOf(HyperOs4LargeIconInputs.Icon(
                "/data/system/theme/large_icons/0/resources/monoicon", context.packageName)))
            val result = HyperOs4IconExporter.exportLargeIcons(context, directory, inputs) { true }!!
            assertEquals(1, result.components)
            assertEquals(3, result.files)
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "png" })
            val rows = File(directory, ".large-icons").readLines()
            assertEquals("all three large spans export independently", 3, rows.size)
            for (row in rows) {
                val (source, target, name) = row.split('\t')
                val span = source.substringAfterLast('/').split('x').map(String::toInt)
                val expected = geometry.dimensions(span[0], span[1])!!
                val bitmap = BitmapFactory.decodeFile(File(directory, "$target/res/drawable/$name").path)!!
                try {
                    assertTrue("glyph has a high-resolution source", minOf(bitmap.width, bitmap.height) >= 512)
                    assertEquals(expected.width / expected.height, bitmap.width.toDouble() / bitmap.height, 0.003)
                } finally { bitmap.recycle() }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun ambiguousOwnershipAndAppPairsKeepOriginalResources() {
        val path = "/data/system/theme/large_icons/0/resources/shared"
        val text = """[{"a":{"packageName":"com.a","path":"$path"},
            "b":{"packageName":"com.b","path":"$path"},
            "pair":{"packageName":"com.c","path":"$path-pair","isPairApp":true},
            "outside":{"packageName":"com.d","path":"/other/path"}}]"""
        assertTrue(HyperOs4LargeIconInputs.parse(text, listOf("/data/system/theme/large_icons/")).isEmpty())
    }
}
