package com.jerrey.monoicon

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperSeedInstrumentedTest {
    @Test fun extractionRetainsTheCallersTinyAndAlreadySampleSizedBitmaps() {
        for ((width, height) in listOf(1 to 1, 128 to 128, 1 to 30_000)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff777777.toInt())
                assertEquals(0xff777777.toInt(), PixelMonetColorEngine.seedFromBitmap(bitmap))
                assertFalse("Extraction must not recycle the supplied bitmap", bitmap.isRecycled)
                assertEquals(0xff777777.toInt(), bitmap.getPixel(0, 0))
            } finally { bitmap.recycle() }
        }
    }

    @Test fun transparentOrRecycledInputHasNoImageDerivedSeed() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertNull(PixelMonetColorEngine.seedFromBitmap(bitmap))
        bitmap.recycle()
        assertNull(PixelMonetColorEngine.seedFromBitmap(bitmap))
    }
}
