package com.jerrey.monoicon.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

// ═══════════════════════════════════════════════════════════════════
// Bitmap Extensions
// ═══════════════════════════════════════════════════════════════════

/**
 * Returns a mutable copy of this [Bitmap] with the same dimensions
 * and configuration, filled with [Bitmap.Config.ARGB_8888].
 *
 * Use this when you need to draw onto a bitmap without mutating the original.
 */
fun Bitmap.copyMutable(): Bitmap {
    val copy = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(copy)
    canvas.drawBitmap(this, 0f, 0f, null)
    return copy
}

/**
 * Returns the total number of pixels in this [Bitmap] (`width * height`).
 */
val Bitmap.pixelCount: Int get() = width * height

/**
 * Creates a new [Bitmap] filled with the given [color].
 */
fun createSolidBitmap(
    width: Int,
    height: Int,
    color: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, config)
    bitmap.eraseColor(color)
    return bitmap
}

// ═══════════════════════════════════════════════════════════════════
// General Extensions
// ═══════════════════════════════════════════════════════════════════

/**
 * Returns `true` if this [String] is not blank (not empty and
 * not consisting solely of whitespace characters).
 */
fun String?.isNotBlankSafe(): Boolean = this != null && this.isNotBlank()

/**
 * Returns the receiver if it is not `null`, otherwise throws
 * [IllegalStateException] with the given [message].
 */
fun <T> T?.requireNotNull(message: String = "Required value was null"): T {
    if (this == null) throw IllegalStateException(message)
    return this
}
