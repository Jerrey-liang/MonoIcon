package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.hook.HyperOs4IconExporter
import com.jerrey.monoicon.hook.HyperOs4LargeIconInputs
import com.jerrey.monoicon.hook.HyperOs4LargeIconGeometry
import com.jerrey.monoicon.profile.ProfileRepository
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HyperOs4ExportInstrumentedTest {
    @Test fun installedLauncherActivitiesExportWithoutAbortingTheBatch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hyperos4-test-export")
        try {
            val profile = ProfileRepository.parse(ProfileRepository.readBounded(
                context.assets.open("hyperos4/profiles/hyperos4-6144.json")), official = true)
            val geometry = HyperOs4LargeIconGeometry.Geometry(100.0, 150.0, 70.0)
            val inputs = HyperOs4LargeIconInputs(geometry, listOf(HyperOs4LargeIconInputs.Icon(
                "/data/system/theme/large_icons/0/resources/monoicon", context.packageName)))
            val result = HyperOs4IconExporter.export(context, directory, profile, inputs) { true }!!
            assertTrue("at least this application's activity must export", result.components > 0)
            assertTrue(File(directory, "com.jerrey.monoicon.MainActivity.png").length() > 8)
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
