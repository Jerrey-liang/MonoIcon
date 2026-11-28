package com.jerrey.monoicon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.jerrey.monoicon.color.IconDrawableCache
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
    @Test fun rendersInstalledAppAndReportsRemovedPackage() {
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
}
