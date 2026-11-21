package com.jerrey.monoicon

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.color.dynamic.MonetPalette
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.mask.MaskBitmapIO
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import com.jerrey.monoicon.ui.screens.IconStyleExample
import com.jerrey.monoicon.ui.screens.IconStylePreviewRepository
import com.jerrey.monoicon.ui.screens.LawniconsApp
import com.jerrey.monoicon.ui.screens.LawniconsAppsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconStylePreviewRepositoryInstrumentedTest {
    @Test
    fun borderOnlyPreviewContainsOnlyDesktopGeneratedAppsForEverySourceMode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val desktopComponents = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).filter { it.activityInfo?.let { info ->
            info.enabled && info.applicationInfo.enabled && info.exported
        } == true }.mapTo(mutableSetOf()) {
            ComponentName(it.activityInfo.packageName, it.activityInfo.name).flattenToString()
        }
        assertTrue("The module provides a real desktop candidate", desktopComponents.any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        })

        for (style in IconSourceStyle.entries) {
            for (allowFallback in listOf(false, true)) {
                val examples = IconStylePreviewRepository.loadExamples(
                    context,
                    style,
                    allowLawniconsFallback = allowFallback,
                    generatedBorderOnly = true,
                )
                val label = "style=$style fallback=$allowFallback"
                assertTrue("$label keeps the real result count instead of filling samples", examples.size <= 6)
                assertTrue("$label contains only installed launcher activities affected by the border", examples.all {
                    it.identity in desktopComponents &&
                        !it.lawnicons && it.source != MaskStrategy.SOURCE_LAWNICONS &&
                        !it.bundledSample && !it.identity.startsWith("framework:") &&
                        adjustsGeneratedBorder(it.source)
                })
                if (style == IconSourceStyle.GENERATED) {
                    assertTrue("$label has a real generated launcher preview", examples.isNotEmpty())
                }
            }
        }
    }

    @Test
    fun directNativePreviewNeverUsesLawniconsEvenAfterItsCacheWasPopulated() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IconStylePreviewRepository.loadExamples(context, IconSourceStyle.LAWNICONS)
        val nativePriority = IconStylePreviewRepository.loadExamples(context, IconSourceStyle.NATIVE_GENERATED,
            allowLawniconsFallback = true)
        assertEquals(6, nativePriority.size)
        assertTrue("Direct native priority generates when the native mask is missing",
            nativePriority.none { it.lawnicons || it.source == MaskStrategy.SOURCE_LAWNICONS || it.bundledSample })
        val nativeOnly = IconStylePreviewRepository.loadExamples(context, IconSourceStyle.NATIVE,
            allowLawniconsFallback = false)
        assertEquals(6, nativeOnly.size)
        assertTrue("Native preview preserves the old global Lawnicons exclusion",
            nativeOnly.none { it.lawnicons || it.source == MaskStrategy.SOURCE_LAWNICONS || it.bundledSample })
        val legacy = IconStylePreviewRepository.loadExamples(context, lawniconsEnabled = false)
        assertTrue("The boolean compatibility entry point also excludes Lawnicons",
            legacy.none { it.lawnicons || it.source == MaskStrategy.SOURCE_LAWNICONS || it.bundledSample })
    }

    @Test
    fun nativePreviewUsesLawniconsForMissingNativeAndKeepsBothChainsSeparateInCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = LawniconsAppsRepository.loadLauncherApps(context).firstOrNull {
            it.hasLawnicons && !it.hasNativeMonochrome
        }
        assertNotNull("The device provides a launcher app with only a Lawnicons authored resource", app)
        val candidate = requireNotNull(app)
        val pack = IconStylePreviewRepository.loadAppExample(context, candidate, IconSourceStyle.LAWNICONS,
            allowLawniconsFallback = true).single()
        assertEquals(MaskStrategy.SOURCE_LAWNICONS, pack.source)
        val direct = IconStylePreviewRepository.loadAppExample(context, candidate, IconSourceStyle.NATIVE_GENERATED,
            allowLawniconsFallback = true).single()
        assertTrue("The direct chain bypasses the populated pack cache",
            direct.source != MaskStrategy.SOURCE_NATIVE && direct.source != MaskStrategy.SOURCE_LAWNICONS)
        val native = IconStylePreviewRepository.loadAppExample(context, candidate, IconSourceStyle.NATIVE,
            allowLawniconsFallback = true).single()
        assertEquals("The default native chain falls back to the authored pack", MaskStrategy.SOURCE_LAWNICONS, native.source)
        val directAgain = IconStylePreviewRepository.loadAppExample(context, candidate, IconSourceStyle.NATIVE_GENERATED,
            allowLawniconsFallback = true).single()
        val nativeAgain = IconStylePreviewRepository.loadAppExample(context, candidate, IconSourceStyle.NATIVE,
            allowLawniconsFallback = true).single()
        assertEquals(direct.source, directAgain.source)
        assertTrue(direct.mask.sameAs(directAgain.mask))
        assertEquals(MaskStrategy.SOURCE_LAWNICONS, nativeAgain.source)
        assertTrue(native.mask.sameAs(nativeAgain.mask))
    }

    @Test
    fun changingToGeneratedStyleDoesNotReuseNativeOrLawniconsPreviewMasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lawnicons = IconStylePreviewRepository.loadExamples(context, IconSourceStyle.LAWNICONS)
        assertTrue("Lawnicons includes a real bundle example", lawnicons.any { it.lawnicons })
        IconStylePreviewRepository.loadExamples(context, IconSourceStyle.NATIVE)
        IconStylePreviewRepository.loadExamples(context, IconSourceStyle.NATIVE_GENERATED)

        val generated = IconStylePreviewRepository.loadExamples(context, IconSourceStyle.GENERATED)
        assertEquals("The same six-icon preview is retained", 6, generated.size)
        assertTrue("Generated style bypasses both authored sources even after their caches are populated",
            generated.all { !it.lawnicons && it.source != MaskStrategy.SOURCE_NATIVE &&
                it.source != MaskStrategy.SOURCE_LAWNICONS })
        assertTrue("Generated style must not display a synthetic Lawnicons sample",
            generated.none { it.bundledSample })
    }

    @Test
    fun artworkRadiusReachesGeneratedPreviewWithoutChangingOuterShapeOrAuthoredIcons() = runBlocking {
        val mask = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(48f, 48f, 144f, 144f, Paint().apply { color = Color.WHITE })
        val examples = listOf(
            IconStyleExample("generated", null, mask, false, source = MaskStrategy.SOURCE_AOSP_LEGACY),
            IconStyleExample("native", null, mask, false, source = MaskStrategy.SOURCE_NATIVE),
            IconStyleExample("lawnicons", null, mask, true),
        )
        val colors = IconThemeColors(Color.BLUE, Color.RED, MonetPalette.generate(Color.BLUE))
        val frames = mutableListOf<List<Bitmap>>()
        try {
            for (radius in listOf(0f, 0.25f, 0.5f)) {
                frames += IconStylePreviewRepository.renderExamples(examples, colors, 100, 100, 1f, radius)
            }
            fun area(bitmap: Bitmap): Int {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count { it == Color.BLUE }
            }
            assertTrue("Increasing the inner radius rounds away square corners", area(frames[0][0]) > area(frames[1][0]))
            assertTrue("The circle endpoint rounds the inner artwork further", area(frames[1][0]) > area(frames[2][0]))
            for (frame in frames) {
                assertEquals(Color.BLUE, frame[0].getPixel(96, 96))
                assertEquals(Color.RED, frame[0].getPixel(96, 16))
                for (y in 0 until 192) for (x in 0 until 192) {
                    assertEquals("Artwork radius preserves outer alpha at $x,$y",
                        Color.alpha(frames[0][0].getPixel(x, y)), Color.alpha(frame[0].getPixel(x, y)))
                }
                for (index in 1..2) assertTrue("Authored source $index ignores artwork radius", frames[0][index].sameAs(frame[index]))
            }
        } finally {
            frames.flatten().forEach(Bitmap::recycle)
            mask.recycle()
        }
    }

    @Test
    fun borderPreviewEnlargesGeneratedArtworkInsideAnUnchangedOuterContour() = runBlocking {
        val mask = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawRect(48f, 48f, 144f, 144f, Paint().apply { color = Color.WHITE })
        val examples = listOf(
            IconStyleExample("generated", null, mask, false, source = MaskStrategy.SOURCE_AOSP_LEGACY),
            IconStyleExample("native", null, mask, false, source = MaskStrategy.SOURCE_NATIVE),
            IconStyleExample("lawnicons", null, mask, true),
        )
        val colors = IconThemeColors(Color.BLUE, Color.RED, MonetPalette.generate(Color.BLUE))
        val full = IconStylePreviewRepository.renderExamples(examples, colors, 100, 100, 1f)
        val reduced = IconStylePreviewRepository.renderExamples(examples, colors, 100, 100, 0.5f)
        val none = IconStylePreviewRepository.renderExamples(examples, colors, 100, 100, 0f)
        try {
            fun foregroundArea(bitmap: Bitmap): Int {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return pixels.count { it == Color.BLUE }
            }
            assertTrue("less border enlarges the preview artwork", foregroundArea(full[0]) < foregroundArea(reduced[0]))
            assertTrue("zero border has the largest artwork", foregroundArea(reduced[0]) < foregroundArea(none[0]))
            for (frame in listOf(full, reduced, none)) {
                assertEquals(Color.BLUE, frame[0].getPixel(96, 96))
                for (y in 0 until 192) for (x in 0 until 192) {
                    assertEquals("preview border preserves outer alpha at $x,$y",
                        Color.alpha(full[0].getPixel(x, y)), Color.alpha(frame[0].getPixel(x, y)))
                }
            }
            assertEquals("default preview retains exterior plate", Color.RED, full[0].getPixel(96, 16))
            assertEquals("zero border brings artwork into the old margin", Color.BLUE, none[0].getPixel(96, 16))
            for (index in 1..2) {
                assertTrue("Authored source $index keeps its plate", full[index].sameAs(reduced[index]))
                assertTrue("Authored source $index keeps its plate", full[index].sameAs(none[index]))
            }
        } finally {
            (full + reduced + none).forEach(Bitmap::recycle)
            mask.recycle()
        }
    }

    @Test
    fun singleSparseSilhouettePreviewUsesTheSameAdjustableBorderAsTheLauncher() = runBlocking {
        val alpha = ByteArray(100 * 100).also {
            for (y in 42 until 58) for (x in 25 until 40) {
                it[y * 100 + x] = -1
                it[y * 100 + 99 - x] = -1
            }
        }
        val mask = MaskBitmapIO.create(alpha, 100, 100)
        val colors = IconThemeColors(Color.BLUE, Color.RED, MonetPalette.generate(Color.BLUE))
        val sources = listOf(MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE,
            MaskStrategy.SOURCE_FOREGROUND, MaskStrategy.SOURCE_LUMINANCE)
        try {
            for (source in sources) {
                val example = IconStyleExample("generated.sparse", "Sparse", mask, false, source = source)
                val artworkAreas = mutableListOf<Int>()
                for (ratio in listOf(1f, 0.5f, 0f)) {
                    val preview = IconStylePreviewRepository.renderExamples(listOf(example), colors, 37, 72, ratio).single()
                    val launcher = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                    val drawable = ColoredMonochromeDrawable(
                        mask, colors.foreground, colors.background,
                        roundnessPercent = 37, sizePercent = 72, dynamicColors = false,
                        generatedBorderRatio = ratio, adjustGeneratedBorder = adjustsGeneratedBorder(source),
                    )
                    drawable.setBounds(0, 0, 192, 192)
                    drawable.draw(Canvas(launcher))
                    try {
                        assertTrue("single-app preview and launcher share source=$source border=$ratio", preview.sameAs(launcher))
                        assertEquals("the two motifs stay disconnected", colors.background, preview.getPixel(96, 96))
                        val pixels = IntArray(192 * 192)
                        preview.getPixels(pixels, 0, 192, 0, 0, 192, 192)
                        artworkAreas += pixels.count { it == colors.foreground }
                    } finally { preview.recycle(); launcher.recycle() }
                }
                assertTrue("generated source=$source grows with less padding: $artworkAreas",
                    artworkAreas[0] < artworkAreas[1] && artworkAreas[1] < artworkAreas[2])
            }
        } finally { mask.recycle() }
    }

    @Test
    fun realAppDetailPreviewsReuseOneGeneratedMaskWhileOnlyTheInteriorBorderChanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val colors = IconThemeColors(Color.BLUE, Color.RED, MonetPalette.generate(Color.BLUE))
        val normalizedDefaultFraction = 0.7f * kotlin.math.sqrt(375f / 576f)
        val defaultMetrics = mutableMapOf<String, Pair<Float, Float>>()
        fun bounds(bitmap: Bitmap, contains: (Int) -> Boolean): Rect {
            var left = bitmap.width
            var top = bitmap.height
            var right = 0
            var bottom = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                if (!contains(bitmap.getPixel(x, y))) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
            return Rect(left, top, right, bottom)
        }
        fun artworkBounds(bitmap: Bitmap): Rect {
            val contrast = maxOf(
                kotlin.math.abs(Color.red(colors.foreground) - Color.red(colors.background)),
                kotlin.math.abs(Color.green(colors.foreground) - Color.green(colors.background)),
                kotlin.math.abs(Color.blue(colors.foreground) - Color.blue(colors.background)),
            )
            // Tonal white maps only 20% away from the plate, so alpha=32 can
            // differ by about six channel levels instead of equalling BLUE.
            val visibleDelta = maxOf(2, contrast * 32 / (255 * 5))
            return bounds(bitmap) { pixel ->
                Color.alpha(pixel) >= 128 && maxOf(
                    kotlin.math.abs(Color.red(pixel) - Color.red(colors.background)),
                    kotlin.math.abs(Color.green(pixel) - Color.green(colors.background)),
                    kotlin.math.abs(Color.blue(pixel) - Color.blue(colors.background)),
                ) >= visibleDelta
            }
        }
        for (packageName in listOf("tv.danmaku.bili", "com.shizi.tool.p3")) {
            val component = pm.getLaunchIntentForPackage(packageName)?.component
            assertNotNull("$packageName must be installed with a launcher activity", component)
            val identity = requireNotNull(component).flattenToString()
            val examples = IconStylePreviewRepository.loadAppExample(
                context,
                LawniconsApp(packageName, packageName, identity),
                IconSourceStyle.GENERATED,
                allowLawniconsFallback = false,
            )
            assertEquals("$packageName contributes exactly one real detail preview", 1, examples.size)
            val example = examples.single()
            assertEquals(identity, example.identity)
            assertEquals(false, example.lawnicons)
            assertEquals(false, example.bundledSample)
            assertTrue("$packageName must use an adjustable generated source, got ${example.source}",
                adjustsGeneratedBorder(example.source))

            // loadAppExample owns this cached mask. Keep it alive and reuse it for every setting;
            // only locally rendered outputs and the independent snapshot may be recycled.
            val savedMask = requireNotNull(example.mask.copy(
                example.mask.config ?: Bitmap.Config.ARGB_8888, false))
            val rendered = mutableListOf<Bitmap>()
            try {
                val default100 = IconStylePreviewRepository.renderExamples(
                    examples, colors, roundnessPercent = 100, sizePercent = 0,
                ).single().also(rendered::add)
                val border100 = IconStylePreviewRepository.renderExamples(
                    examples, colors, roundnessPercent = 100, sizePercent = 0,
                    generatedIconBorderRatio = 1f,
                ).single().also(rendered::add)
                val border50 = IconStylePreviewRepository.renderExamples(
                    examples, colors, roundnessPercent = 100, sizePercent = 0,
                    generatedIconBorderRatio = 0.5f,
                ).single().also(rendered::add)
                val border0 = IconStylePreviewRepository.renderExamples(
                    examples, colors, roundnessPercent = 100, sizePercent = 0,
                    generatedIconBorderRatio = 0f,
                ).single().also(rendered::add)
                val userSize100 = IconStylePreviewRepository.renderExamples(
                    examples, colors, roundnessPercent = 100, sizePercent = 100,
                    generatedIconBorderRatio = 1f,
                ).single().also(rendered::add)

                assertTrue("$packageName default argument selects the explicit normalized 100% border",
                    default100.sameAs(border100))
                assertSameAlpha(border100, border50, "$packageName 100% to 50%")
                assertSameAlpha(border100, border0, "$packageName 100% to 0%")
                assertEquals("$packageName circular preview keeps transparent corners", 0,
                    Color.alpha(border100.getPixel(0, 0)))
                assertEquals("$packageName circular preview keeps its opaque centre", 255,
                    Color.alpha(border100.getPixel(border100.width / 2, border100.height / 2)))

                assertMeaningfulContentChange(border100, border50, "$packageName 100% to 50%")
                assertMeaningfulContentChange(border50, border0, "$packageName 50% to 0%")
                val plate = bounds(border100) { Color.alpha(it) >= 128 }
                val defaultArtwork = artworkBounds(border100)
                val zeroArtwork = artworkBounds(border0)
                assertTrue("$packageName normalized default artwork must be visible", !defaultArtwork.isEmpty)
                val plateCenterX = plate.exactCenterX()
                val plateCenterY = plate.exactCenterY()
                val maximumCenteredExtent = 2f * maxOf(
                    plateCenterX - defaultArtwork.left, defaultArtwork.right - plateCenterX,
                    plateCenterY - defaultArtwork.top, defaultArtwork.bottom - plateCenterY,
                )
                val minimumGap = minOf(
                    defaultArtwork.left - plate.left, plate.right - defaultArtwork.right,
                    defaultArtwork.top - plate.top, plate.bottom - defaultArtwork.bottom,
                ).toFloat()
                val plateShortSide = minOf(plate.width(), plate.height()).toFloat()
                assertEquals("$packageName default uses the common maximum centered artwork extent",
                    normalizedDefaultFraction * plateShortSide, maximumCenteredExtent, 2f)
                assertEquals("$packageName default uses the common minimum exterior margin",
                    (1f - normalizedDefaultFraction) * plateShortSide / 2f, minimumGap, 2f)
                defaultMetrics[packageName] = maximumCenteredExtent to minimumGap

                val zeroMaximumExtent = 2f * maxOf(
                    plateCenterX - zeroArtwork.left, zeroArtwork.right - plateCenterX,
                    plateCenterY - zeroArtwork.top, zeroArtwork.bottom - plateCenterY,
                )
                assertTrue("$packageName 0% retains cover-fit beyond the normalized default",
                    zeroMaximumExtent > maximumCenteredExtent)

                val maskPixels = IntArray(example.mask.width * example.mask.height)
                example.mask.getPixels(maskPixels, 0, example.mask.width, 0, 0,
                    example.mask.width, example.mask.height)
                val maximumAlpha = maskPixels.maxOf { Color.alpha(it) }
                val sourceThreshold = minOf(32, maximumAlpha)
                val sourceBounds = bounds(example.mask) { Color.alpha(it) >= sourceThreshold }
                val sourceExtent = maxOf(
                    example.mask.width / 2f - sourceBounds.left,
                    sourceBounds.right - example.mask.width / 2f,
                    example.mask.height / 2f - sourceBounds.top,
                    sourceBounds.bottom - example.mask.height / 2f,
                )
                val baseScale = minOf(plate.width().toFloat() / example.mask.width,
                    plate.height().toFloat() / example.mask.height)
                val defaultScale = normalizedDefaultFraction * plateShortSide /
                    (2f * sourceExtent * baseScale)
                assertEquals("$packageName normalized default preserves the source horizontal center",
                    plateCenterX + (sourceBounds.exactCenterX() - example.mask.width / 2f) *
                        baseScale * defaultScale,
                    defaultArtwork.exactCenterX(), 2f)
                assertEquals("$packageName normalized default preserves the source vertical center",
                    plateCenterY + (sourceBounds.exactCenterY() - example.mask.height / 2f) *
                        baseScale * defaultScale,
                    defaultArtwork.exactCenterY(), 2f)
                assertTrue("$packageName whole-icon coverage changes only when icon size changes",
                    opaqueArea(userSize100) > opaqueArea(border100))
                assertTrue("$packageName rendering must not edit the cached generated mask",
                    savedMask.sameAs(example.mask))
            } finally {
                rendered.forEach(Bitmap::recycle)
                savedMask.recycle()
            }
        }
        val bilibili = requireNotNull(defaultMetrics["tv.danmaku.bili"])
        val shizi = requireNotNull(defaultMetrics["com.shizi.tool.p3"])
        assertEquals("real app defaults share one maximum centered artwork extent",
            bilibili.first, shizi.first, 2f)
        assertEquals("real app defaults share one minimum exterior margin",
            bilibili.second, shizi.second, 2f)
    }

    private fun assertSameAlpha(expected: Bitmap, actual: Bitmap, label: String) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            assertEquals("$label preserves outer alpha at $x,$y",
                Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
        }
    }

    private fun assertMeaningfulContentChange(first: Bitmap, second: Bitmap, label: String) {
        var changed = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        }
        assertTrue("$label changes real rendered content: $changed",
            changed > first.width * first.height / 100)
    }

    private fun opaqueArea(image: Bitmap): Int {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.count { Color.alpha(it) == 255 }
    }
}
