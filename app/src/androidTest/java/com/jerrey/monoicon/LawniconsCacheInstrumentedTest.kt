package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.cache.MonochromeCache
import com.jerrey.monoicon.theme.mask.LawniconsAssetSource
import com.jerrey.monoicon.theme.mask.LawniconsBundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LawniconsCacheInstrumentedTest {

    @Test
    fun repeatedAssetAndAliasLookupsAvoidReadsAndKeepIdenticalPixels() {
        val bundle = CountingBundle("v1", glyphPng())
        val cache = MonochromeCache(64 * 1024)
        val first = LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache)
        val repeated = LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache)
        val alias = LawniconsAssetSource.lookupMask(ALIAS, 64, bundle, cache)

        assertNotNull(first)
        assertSame(first, repeated)
        assertSame(first, alias)
        assertEquals("A cache hit must not reopen/decode the PNG", 1, bundle.opens)

        val uncached = LawniconsAssetSource.lookupMask(
            PACKAGE, 64, bundle.index(), bundle.aliases(), bundle::open,
        )
        assertTrue("Cached raster pixels match the existing path", first!!.sameAs(uncached))
    }

    @Test
    fun targetSizeBundleVersionAndClearInvalidateCachedMasks() {
        val png = glyphPng()
        val bundle = CountingBundle("v1", png)
        val cache = MonochromeCache(64 * 1024)
        val first = LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache)
        val smaller = LawniconsAssetSource.lookupMask(PACKAGE, 32, bundle, cache)
        assertEquals(32, smaller!!.width)
        assertNotSame(first, smaller)
        assertEquals(2, bundle.opens)

        val updated = CountingBundle("v2", png)
        assertNotSame(first, LawniconsAssetSource.lookupMask(PACKAGE, 64, updated, cache))
        assertEquals("A new bundle version must read its own asset", 1, updated.opens)

        cache.clear()
        assertNotSame(first, LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache))
        assertEquals(3, bundle.opens)
    }

    @Test
    fun assetMasksStayInsideTheExistingByteBudget() {
        val bundle = CountingBundle("v1", glyphPng())
        val budget = 64 * 64
        val cache = MonochromeCache(budget)
        val first = LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache)
        assertNotNull(first)

        // A mask larger than the entire budget may be returned to the caller,
        // but must not remain retained by the cache.
        assertNotNull(LawniconsAssetSource.lookupMask(PACKAGE, 96, bundle, cache))
        assertTrue(cache.size <= budget)
        assertNotSame(first, LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache))
        assertEquals("Evicted masks are decoded again on demand", 3, bundle.opens)
        assertTrue(cache.size <= budget)
    }

    @Test
    fun failedReadsAreNotCached() {
        val bundle = CountingBundle("v1", glyphPng()).apply { failNextRead = true }
        val cache = MonochromeCache(64 * 1024)

        assertNull(LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache))
        val recovered = LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache)
        assertNotNull(recovered)
        assertSame(recovered, LawniconsAssetSource.lookupMask(PACKAGE, 64, bundle, cache))
        assertEquals(2, bundle.opens)
    }

    private class CountingBundle(
        private val bundleVersion: String,
        private val png: ByteArray,
    ) : LawniconsBundle {
        var opens = 0
        var failNextRead = false

        override fun version() = bundleVersion
        override fun index() = mapOf(PACKAGE to "glyph")
        override fun aliases() = mapOf(ALIAS to "glyph")

        override fun open(asset: String): InputStream? {
            opens++
            if (failNextRead) {
                failNextRead = false
                return null
            }
            return if (asset == "glyph") ByteArrayInputStream(png) else null
        }
    }

    private fun glyphPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawCircle(16f, 16f, 10f, Paint().apply { color = Color.WHITE })
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val PACKAGE = "com.example.cache"
        const val ALIAS = "com.example.alias"
    }
}
