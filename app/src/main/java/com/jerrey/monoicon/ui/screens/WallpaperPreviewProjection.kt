package com.jerrey.monoicon.ui.screens

/** Bitmap destination relative to a preview's absolute position on the display. */
internal data class WallpaperPreviewProjection(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Center-crop the wallpaper to the whole display, then offset that projection
 * into the preview. Scrolling changes the sampled region, not the image scale.
 */
internal fun wallpaperPreviewProjection(
    bitmapWidth: Int,
    bitmapHeight: Int,
    displayWidth: Int,
    displayHeight: Int,
    previewLeft: Float,
    previewTop: Float,
): WallpaperPreviewProjection {
    require(bitmapWidth > 0 && bitmapHeight > 0 && displayWidth > 0 && displayHeight > 0)
    val scale = maxOf(displayWidth.toFloat() / bitmapWidth, displayHeight.toFloat() / bitmapHeight)
    val width = bitmapWidth * scale
    val height = bitmapHeight * scale
    val left = (displayWidth - width) / 2f - previewLeft
    val top = (displayHeight - height) / 2f - previewTop
    return WallpaperPreviewProjection(left, top, left + width, top + height)
}
