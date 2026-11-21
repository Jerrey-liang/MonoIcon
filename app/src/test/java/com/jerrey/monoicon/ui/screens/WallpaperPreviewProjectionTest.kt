package com.jerrey.monoicon.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperPreviewProjectionTest {
    @Test
    fun matchingAspectRatioUsesThePreviewPositionOnTheFullDisplay() {
        val projection = wallpaperPreviewProjection(400, 800, 400, 800, 20f, 500f)
        assertEquals(-20f, projection.left, 0f)
        assertEquals(-500f, projection.top, 0f)
        assertEquals(380f, projection.right, 0f)
        assertEquals(300f, projection.bottom, 0f)
    }

    @Test
    fun wideWallpaperIsCenterCroppedToTheDisplayBeforeSamplingTheCard() {
        val projection = wallpaperPreviewProjection(1200, 800, 400, 800, 20f, 500f)
        assertEquals(-420f, projection.left, 0f)
        assertEquals(-500f, projection.top, 0f)
        assertEquals(780f, projection.right, 0f)
        assertEquals(300f, projection.bottom, 0f)
    }

    @Test
    fun scrollingMovesTheSampleWithoutChangingItsScale() {
        val before = wallpaperPreviewProjection(600, 400, 400, 800, 20f, 500f)
        val after = wallpaperPreviewProjection(600, 400, 400, 800, 20f, 350f)
        assertEquals(before.right - before.left, after.right - after.left, 0f)
        assertEquals(before.bottom - before.top, after.bottom - after.top, 0f)
        assertEquals(150f, after.top - before.top, 0f)
    }
}
