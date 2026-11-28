package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.theme.archive.LargeIconResources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class LargeIconCatalogInstrumentedTest {
    @Test fun discoversOnlySelectedStaticIconsAcrossSupportedSpansAndDensities() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(cache.toPath(), "large-catalog-").toFile()
        try {
            val selected = "com.example.selected"
            val paired = "com.example.pair"
            val dynamic = "com.example.dynamic"
            val unselected = "com.example.absent"
            val mismatched = "com.example.mismatch"
            val spans = mapOf("1x2" to (2 to 4), "2x1" to (4 to 2), "2x2" to (4 to 4))
            val buckets = listOf("drawable", "drawable-nxhdpi", "drawable-xxhdpi", "drawable-xxxhdpi",
                "drawable-sw720dp", "drawable-sw720dp-xxxhdpi")
            val expected = mutableMapOf<String, Pair<Int, Int>>()
            for ((span, size) in spans) for (bucket in buckets) {
                val path = "$selected/$span/res/$bucket/$selected.png"
                writePng(File(root, path), size.first, size.second)
                expected[path] = size
            }
            for (pkg in listOf(paired, dynamic, unselected, mismatched)) {
                writePng(File(root, "$pkg/2x2/res/drawable-xxxhdpi/$pkg.png"), 4, 4)
            }
            File(root, "$dynamic/2x2/res/fancy_icons").mkdir()
            writePng(File(root, "$selected/1x1/res/drawable-xxxhdpi/$selected.png"), 2, 2)
            writePng(File(root, "$selected/2x2/res/drawable-unknown/$selected.png"), 4, 4)
            writePng(File(root, "$selected/2x2/res/drawable-xxxhdpi/com.example.other.png"), 4, 4)
            val wrongPng = File(root, "$selected/2x2/res/drawable-mdpi/$selected.png")
            checkNotNull(wrongPng.parentFile).mkdirs()
            wrongPng.writeText("not PNG")

            val config = File(root, LargeIconResources.CONFIG)
            assertTrue(LargeIconResources.scan(root).isEmpty())
            config.writeText(JSONObject()
                .put("selected", entry(root, selected))
                .put("pair", entry(root, paired).put("isPairApp", true))
                .put("dynamic", entry(root, dynamic))
                .put("outside", entry(root, mismatched).put("path", File(root, selected).absolutePath))
                .toString())

            val assets = LargeIconResources.scan(root)
            assertEquals(expected.keys.sorted(), assets.map { it.path })
            for (asset in assets) {
                assertEquals(expected[asset.path], asset.width to asset.height)
                assertEquals(selected, asset.pkg)
                assertEquals(LargeIconResources.digest(File(root, asset.path)), asset.sha)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun entry(root: File, pkg: String) = JSONObject()
        .put("packageName", pkg)
        .put("path", File(root, pkg).absolutePath)
        .put("isPairApp", false)

    private fun writePng(file: File, width: Int, height: Int) {
        checkNotNull(file.parentFile).mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
