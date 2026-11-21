package com.jerrey.monoicon.theme.wallpaper

import com.jerrey.monoicon.material2025.quantize.QuantizerCelebi
import com.jerrey.monoicon.material2025.score.Score
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/** Bounds apply before allocating an image, including to malformed cache/provider input. */
internal object WallpaperImagePolicy {
    const val MAX_ENCODED_BYTES = 32 * 1024 * 1024
    const val MAX_IMAGE_PIXELS = 1_500_000
    const val MAX_IMAGE_EDGE = 1536
    const val MAX_SEED_EDGE = 128
    const val STABLE_FALLBACK_COLOR = 0xff4285f4.toInt()

    fun dimensions(width: Int, height: Int, edge: Int, pixels: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && edge > 0 && pixels > 0)
        val scale = min(1.0, min(edge.toDouble() / maxOf(width, height), sqrt(pixels.toDouble() / width / height)))
        return maxOf(1, floor(width * scale).toInt()) to maxOf(1, floor(height * scale).toInt())
    }

    fun seedFromPixels(pixels: IntArray): Int? {
        require(pixels.size <= MAX_SEED_EDGE * MAX_SEED_EDGE)
        // Transparent pixels are not wallpaper colors. The source is composited as opaque by Android.
        val opaque = pixels.filter { it ushr 24 >= 128 }.map { it or 0xff000000.toInt() }.toIntArray()
        if (opaque.isEmpty()) return null
        val population = QuantizerCelebi.quantize(opaque, 128)
        val dominant = population.maxByOrNull { it.value }?.key ?: return null
        // An achromatic image must keep its own dominant color instead of MCU's default blue.
        return Score.score(population, 1, dominant, true).firstOrNull()
    }

    fun copyBounded(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            total += count
            require(total <= MAX_ENCODED_BYTES) { "Wallpaper image exceeds size limit" }
            output.write(buffer, 0, count)
        }
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun mayReadSnapshot(ownerUid: Int, callerUid: Int, verifiedPackages: Set<String>): Boolean =
        ownerUid / 100_000 == callerUid / 100_000 && (ownerUid == callerUid ||
            verifiedPackages.any { it == "com.miui.home" || it == "com.android.systemui" })
}

/** Image and seed travel in one atomic file; readers cannot combine different generations. */
internal data class WallpaperCacheRecord(
    val userId: Int,
    val wallpaperId: Int,
    val capturedAtMillis: Long,
    val seedColor: Int,
    val source: WallpaperProvenance,
    val imageDigest: String,
    val png: ByteArray,
) {
    fun write(output: OutputStream) {
        require(png.size in 1..WallpaperImagePolicy.MAX_ENCODED_BYTES)
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(VERSION)
        data.writeInt(userId)
        data.writeInt(wallpaperId)
        data.writeLong(capturedAtMillis)
        data.writeInt(seedColor)
        data.writeUTF(source.name)
        data.writeUTF(imageDigest)
        data.writeInt(png.size)
        data.write(png)
        data.flush()
    }

    companion object {
        private const val MAGIC = 0x4d495750
        private const val VERSION = 1

        fun read(input: InputStream, expectedUser: Int): WallpaperCacheRecord {
            val data = DataInputStream(input)
            require(data.readInt() == MAGIC && data.readInt() == VERSION) { "Invalid wallpaper cache" }
            val user = data.readInt()
            require(user == expectedUser) { "Wallpaper belongs to another Android user" }
            val wallpaper = data.readInt()
            val captured = data.readLong()
            val seed = data.readInt()
            val source = WallpaperProvenance.valueOf(data.readUTF())
            require(source != WallpaperProvenance.CACHED_IMAGE)
            val digest = data.readUTF()
            require(digest.matches(Regex("[0-9a-f]{64}")))
            val size = data.readInt()
            require(size in 1..WallpaperImagePolicy.MAX_ENCODED_BYTES) { "Invalid wallpaper cache size" }
            val png = ByteArray(size).also(data::readFully)
            require(data.read() == -1 && WallpaperImagePolicy.digest(png) == digest) { "Wallpaper cache checksum mismatch" }
            return WallpaperCacheRecord(user, wallpaper, captured, seed, source, digest, png)
        }
    }
}

enum class WallpaperProvenance { WALLPAPER_FILE, ROOT_FILE, ROOT_WALLPAPER_SCREENSHOT, CACHED_IMAGE, STABLE_FALLBACK }
enum class WallpaperStatus { LOADING, READY, CACHED, UNAVAILABLE }
