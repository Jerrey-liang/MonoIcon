package com.jerrey.monoicon.theme.wallpaper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class WallpaperImagePolicyTest {
    @Test fun colorfulSeedComesFromPixelsAndIsRepeatable() {
        val pixels = IntArray(16_384) { if (it < 14_000) 0xffa62b58.toInt() else 0xff777777.toInt() }
        val seed = WallpaperImagePolicy.seedFromPixels(pixels)
        assertEquals(0xffa62b58.toInt(), seed)
        assertEquals(seed, WallpaperImagePolicy.seedFromPixels(pixels))
    }

    @Test fun greyWallpaperDoesNotAcquireTheScoreBlueDefault() {
        assertEquals(0xff777777.toInt(), WallpaperImagePolicy.seedFromPixels(IntArray(100) { 0xff777777.toInt() }))
        assertNull(WallpaperImagePolicy.seedFromPixels(IntArray(100) { 0x00777777 }))
    }

    @Test fun decodingAndSeedSamplingAreBoundedForExtremeAspectRatios() {
        for ((width, height) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE, 1080 to 2400)) {
            val image = WallpaperImagePolicy.dimensions(width, height, WallpaperImagePolicy.MAX_IMAGE_EDGE,
                WallpaperImagePolicy.MAX_IMAGE_PIXELS)
            assertTrue(image.first > 0 && image.second > 0)
            assertTrue(maxOf(image.first, image.second) <= WallpaperImagePolicy.MAX_IMAGE_EDGE)
            assertTrue(image.first.toLong() * image.second <= WallpaperImagePolicy.MAX_IMAGE_PIXELS)
            val seed = WallpaperImagePolicy.dimensions(width, height, 128, 16_384)
            assertTrue(seed.first * seed.second <= 16_384)
            assertTrue(maxOf(seed.first, seed.second) <= 128)
        }
    }

    @Test fun snapshotAccessUsesActualSameUserUidPackages() {
        val moduleUid = 10_345
        assertTrue(WallpaperImagePolicy.mayReadSnapshot(moduleUid, moduleUid, emptySet()))
        assertTrue(WallpaperImagePolicy.mayReadSnapshot(moduleUid, 10_222, setOf("com.miui.home")))
        assertTrue(WallpaperImagePolicy.mayReadSnapshot(moduleUid, 1000, setOf("android", "com.android.systemui")))
        assertFalse(WallpaperImagePolicy.mayReadSnapshot(moduleUid, 110_222, setOf("com.miui.home")))
        assertFalse(WallpaperImagePolicy.mayReadSnapshot(moduleUid, 10_444, setOf("untrusted.app")))
    }

    @Test fun atomicRecordRoundTripKeepsImageAndSeedInOneGeneration() {
        val image = byteArrayOf(1, 2, 3, 4)
        val record = WallpaperCacheRecord(10, 72, 1234, 0xff123456.toInt(), WallpaperProvenance.ROOT_FILE,
            WallpaperImagePolicy.digest(image), image)
        val encoded = ByteArrayOutputStream().apply { record.write(this) }.toByteArray()
        val actual = WallpaperCacheRecord.read(ByteArrayInputStream(encoded), 10)
        assertEquals(record.seedColor, actual.seedColor)
        assertEquals(record.wallpaperId, actual.wallpaperId)
        assertEquals(record.source, actual.source)
        assertArrayEquals(image, actual.png)
        assertFails { WallpaperCacheRecord.read(ByteArrayInputStream(encoded), 0) }
        encoded[encoded.lastIndex] = 5
        assertFails { WallpaperCacheRecord.read(ByteArrayInputStream(encoded), 10) }
    }

    @Test fun malformedAndOversizedCacheIsRejectedBeforeImageAllocation() {
        val encoded = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x4d495750); writeInt(1); writeInt(0); writeInt(1); writeLong(0)
                writeInt(0); writeUTF(WallpaperProvenance.ROOT_FILE.name); writeUTF("0".repeat(64))
                writeInt(WallpaperImagePolicy.MAX_ENCODED_BYTES + 1)
            }
        }.toByteArray()
        assertFails { WallpaperCacheRecord.read(ByteArrayInputStream(encoded), 0) }
        assertFails { WallpaperCacheRecord.read(ByteArrayInputStream(byteArrayOf(1, 2)), 0) }
    }

    private fun assertFails(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: Exception) { failed = true }
        assertTrue("Malformed record must fail", failed)
    }
}
