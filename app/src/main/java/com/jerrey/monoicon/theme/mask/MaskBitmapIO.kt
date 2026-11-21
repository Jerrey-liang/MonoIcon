package com.jerrey.monoicon.theme.mask

import android.graphics.Bitmap
import java.nio.ByteBuffer

/** ALPHA_8 rows can be padded when an icon has an odd intrinsic width. */
internal object MaskBitmapIO {
    fun create(alpha: ByteArray, width: Int, height: Int): Bitmap {
        require(alpha.size == width * height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val rows = if (bitmap.rowBytes == width) alpha else {
            ByteArray(bitmap.rowBytes * height).also { padded ->
                repeat(height) { y -> alpha.copyInto(padded, y * bitmap.rowBytes, y * width, (y + 1) * width) }
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rows))
        return bitmap
    }

    fun read(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }
    }
}
