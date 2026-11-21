package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.theme.mask.MaskBitmapIO
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeneratedArtworkRenderingInstrumentedTest {
    @Test fun roundingAffectsInnerArtworkAndKeepsPlateAlphaAndSourceAcrossPalettes() {
        val mask = squareMask()
        val original = requireNotNull(mask.copy(Bitmap.Config.ARGB_8888, false))
        try {
            for ((foreground, background) in listOf(Color.BLACK to Color.LTGRAY, Color.WHITE to Color.DKGRAY)) {
                val images = listOf(0f, 0.25f, 0.5f).map { radius ->
                    render(drawable(mask, foreground, background, radius), 240, 240)
                }
                try {
                    val pixels = images.map { image -> IntArray(240 * 240).also { image.getPixels(it, 0, 240, 0, 0, 240, 240) } }
                    for (i in pixels[0].indices) for (image in 1..2) {
                        assertEquals("outer plate coverage is independent of inner roundness",
                            Color.alpha(pixels[0][i]), Color.alpha(pixels[image][i]))
                    }
                    assertFalse("intermediate roundness changes the inner corners", images[0].sameAs(images[1]))
                    assertFalse("circle changes intermediate corners", images[1].sameAs(images[2]))
                    for (image in images) assertEquals("center tone is retained", foreground, image.getPixel(120, 120))
                    assertTrue("cached source is unchanged", original.sameAs(mask))
                } finally { images.forEach(Bitmap::recycle) }
            }
        } finally { original.recycle(); mask.recycle() }
    }

    @Test fun nativeAndLawniconsIgnoreArtworkShapeAndClonesRetainRoundedGeneratedGeometry() {
        val mask = squareMask()
        try {
            val nativeDefault = render(drawable(mask, Color.BLACK, Color.LTGRAY, 0f, generated = false), 240, 240)
            val nativeRounded = render(drawable(mask, Color.BLACK, Color.LTGRAY, 0.5f, generated = false), 240, 240)
            try { assertTrue("authored monochrome resource is unchanged", nativeDefault.sameAs(nativeRounded)) }
            finally { nativeDefault.recycle(); nativeRounded.recycle() }
            for ((width, height) in listOf(240 to 240, 480 to 240, 240 to 480)) for (border in listOf(0f, 0.5f, 1f)) {
                val icon = drawable(mask, Color.BLACK, Color.LTGRAY, 0.5f, border).forLargeIcon(width, height)
                val output = render(icon, width, height)
                val clone = render(icon.constantState.newDrawable(), width, height)
                try { assertTrue("clones keep rounded artwork at $width x $height and border $border", output.sameAs(clone)) }
                finally { output.recycle(); clone.recycle() }
            }
        } finally { mask.recycle() }
    }

    @Test fun alphaOnlyMasksAndTonalMasksUseTheSameRoundedFootprint() {
        val tonal = squareMask()
        val alpha = MaskBitmapIO.create(MaskBitmapIO.read(tonal), tonal.width, tonal.height)
        try {
            val alphaIcon = render(drawable(alpha, Color.WHITE, Color.DKGRAY, 0.5f), 240, 240)
            val tonalIcon = render(ColoredMonochromeDrawable(tonal, Color.WHITE, Color.DKGRAY,
                roundnessPercent = 100, sizePercent = 100, dynamicColors = false,
                adjustGeneratedBorder = true, tonal = true, generatedArtworkRadiusFraction = 0.5f), 240, 240)
            try { assertTrue("alpha and white tonal masks retain the same artwork", alphaIcon.sameAs(tonalIcon)) }
            finally { alphaIcon.recycle(); tonalIcon.recycle() }
        } finally { tonal.recycle(); alpha.recycle() }
    }

    private fun drawable(mask: Bitmap, foreground: Int, background: Int, radius: Float,
                         border: Float = 1f, generated: Boolean = true) = ColoredMonochromeDrawable(
        mask, foreground, background, roundnessPercent = 100, sizePercent = 100, dynamicColors = false,
        generatedBorderRatio = border, adjustGeneratedBorder = generated, generatedArtworkRadiusFraction = radius,
    )

    private fun squareMask() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRect(20f, 20f, 80f, 80f, Paint().apply { color = Color.WHITE })
    }

    private fun render(drawable: Drawable, width: Int, height: Int) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }
}
