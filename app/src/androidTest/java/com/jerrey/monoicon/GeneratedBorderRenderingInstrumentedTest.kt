package com.jerrey.monoicon

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.content.res.Resources
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.mask.AospMonochromeFactory
import com.jerrey.monoicon.theme.mask.AospMonochromeMaskStrategy
import com.jerrey.monoicon.theme.mask.MaskBitmapIO
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.render.ColoredMonochromeDrawable
import com.jerrey.monoicon.theme.render.IconAppearance
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class GeneratedBorderRenderingInstrumentedTest {
    @Test fun gradientPlateLeavesVisibleExteriorPlateAcrossPalettesShapesAndClones() {
        val adaptive = AdaptiveIconDrawable(gradientPlate(), detailedForeground())
        val generated = requireNotNull(AospMonochromeMaskStrategy(
            sourceStyle = IconSourceStyle.GENERATED,
            allowLawniconsFallback = false,
        ).generate(adaptive, null))
        val mask = requireNotNull(generated.mask)
        val originalMask = requireNotNull(mask.copy(Bitmap.Config.ARGB_8888, false))
        try {
            assertForegroundOnlyTonal(generated, adaptive)
            assertVisibleBorderAcrossPalettesAndClones(generated, "synthetic gradient")
            assertTrue("border rendering never edits foreground tone or coverage", originalMask.sameAs(mask))
        } finally {
            originalMask.recycle()
            mask.recycle()
        }
    }

    @Test fun structuredOpaqueBackgroundKeepsCombinedArtworkGeometry() {
        val structuredBackground = paintedLayer { canvas, paint ->
            paint.color = Color.rgb(82, 104, 255)
            canvas.drawRect(0f, 0f, 100f, 100f, paint)
            paint.color = Color.rgb(255, 220, 40)
            canvas.drawRect(6f, 35f, 26f, 65f, paint)
            paint.color = Color.BLACK
            canvas.drawCircle(84f, 50f, 10f, paint)
        }
        val adaptive = AdaptiveIconDrawable(structuredBackground, detailedForeground())
        val generated = requireNotNull(AospMonochromeMaskStrategy(
            sourceStyle = IconSourceStyle.GENERATED,
            allowLawniconsFallback = false,
        ).generate(adaptive, null))
        val mask = requireNotNull(generated.mask)
        val originalMask = requireNotNull(mask.copy(Bitmap.Config.ARGB_8888, false))
        try {
            assertEquals(MaskStrategy.SOURCE_AOSP_ADAPTIVE, generated.source)
            assertTrue("background-layer logos are artwork, not a smooth plate",
                AospMonochromeFactory.hasBackgroundArtwork(adaptive, mask.width))
            assertEquals("left background motif remains opaque outside the foreground", 255,
                Color.alpha(mask.getPixel(2, mask.height / 2)))
            assertTrue("left background motif keeps its bright tone",
                Color.red(mask.getPixel(2, mask.height / 2)) > 200)
            val full = render(generatedDrawable(
                generated, Color.BLACK, Color.LTGRAY, 37, 1f,
            ), 240, 240)
            val reducedIcon = generatedDrawable(
                generated, Color.BLACK, Color.LTGRAY, 37, 0f,
            )
            val reduced = render(reducedIcon, 240, 240)
            val clone = render(reducedIcon.constantState.newDrawable(), 240, 240)
            try {
                assertEquals("combined geometry keeps the same visible tile bounds",
                    pixelBounds(full) { Color.alpha(it) >= 128 },
                    pixelBounds(reduced) { Color.alpha(it) >= 128 })
                assertTrue("clone keeps the combined background artwork policy", reduced.sameAs(clone))
                assertTrue("combined artwork rendering leaves its source mask unchanged", originalMask.sameAs(mask))
            } finally {
                full.recycle()
                reduced.recycle()
                clone.recycle()
            }
        } finally {
            originalMask.recycle()
            mask.recycle()
        }
    }

    @Test fun bilibiliFixtureLeavesVisibleExteriorPlateAndKeepsWhiteArtwork() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val background = assets.open("generated-border-bilibili/background.png").use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        val foreground = assets.open("generated-border-bilibili/foreground.png").use {
            requireNotNull(BitmapFactory.decodeStream(it))
        }
        try {
            val adaptive = AdaptiveIconDrawable(fixtureLayer(background), fixtureLayer(foreground))
            val generated = requireNotNull(AospMonochromeMaskStrategy(
                sourceStyle = IconSourceStyle.GENERATED,
                allowLawniconsFallback = false,
            ).generate(adaptive, null))
            val mask = requireNotNull(generated.mask)
            val saved = requireNotNull(mask.copy(Bitmap.Config.ARGB_8888, false))
            try {
                assertForegroundOnlyTonal(generated, adaptive)
                assertVisibleBorderAcrossPalettesAndClones(generated, "captured bilibili")
                assertTrue("bilibili rendering keeps white detail and source alpha", saved.sameAs(mask))
            } finally { saved.recycle(); mask.recycle() }
        } finally {
            background.recycle()
            foreground.recycle()
        }
    }

    @Test fun installedBilibiliRawResourceExportsVisibleBorderInBothPalettes() {
        assertInstalledRawResourceBorder(
            packageName = "tv.danmaku.bili", evidenceName = "bilibili",
            requireForegroundOnlyAdaptive = true,
        )
    }

    @Test fun installedShiziRawResourceFitsArtworkWithoutChangingTheCircle() {
        assertInstalledRawResourceBorder(
            packageName = "com.shizi.tool.p3", evidenceName = "shizi",
        )
    }

    @Test fun realBilibiliAndShiziUseTheSameNormalizedDefaultBorderAcrossShapesAndSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "normalized-border-default-comparison")
        assertTrue("comparison evidence directory is available", output.isDirectory || output.mkdirs())
        val generated = linkedMapOf<String, com.jerrey.monoicon.mask.GenerateResult>()
        val savedMasks = linkedMapOf<String, Bitmap>()
        val measurements = org.json.JSONArray()
        try {
            for (packageName in listOf("tv.danmaku.bili", "com.shizi.tool.p3")) {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                val resources = context.packageManager.getResourcesForApplication(info)
                val raw = resources.getDrawable(info.icon, null)
                val result = requireNotNull(AospMonochromeMaskStrategy(
                    sourceStyle = IconSourceStyle.GENERATED, allowLawniconsFallback = false,
                ).generate(raw, null))
                assertTrue("$packageName uses generated border geometry", adjustsGeneratedBorder(result.source))
                generated[packageName] = result
                val mask = requireNotNull(result.mask)
                savedMasks[packageName] = requireNotNull(mask.copy(requireNotNull(mask.config), false))
            }
            for ((paletteIndex, palette) in palettes.withIndex()) for (roundness in listOf(0, 100)) {
                val paletteName = if (paletteIndex == 0) "light" else "dark"
                val smallPlateWidths = mutableMapOf<String, Int>()
                for (sizePercent in listOf(0, 100)) {
                    val defaults = mutableMapOf<String, DefaultBorderMetrics>()
                    for ((packageName, result) in generated) {
                        val label = "$packageName $paletteName roundness=$roundness size=$sizePercent"
                        val appName = if (packageName == "tv.danmaku.bili") "bilibili" else "shizi"
                        val prefix = "$appName-$paletteName-r$roundness-s$sizePercent"
                        val images = mutableListOf<Bitmap>()
                        val scales = mutableListOf<Float>()
                        try {
                            for (ratio in ratios) {
                                val icon = generatedDrawable(result, palette.first, palette.second,
                                    roundness, ratio, size = sizePercent)
                                val image = render(icon, 256, 256)
                                images += image
                                scales += renderedGlyphScale(icon)
                                assertArtworkContained(requireNotNull(result.mask), icon, roundness,
                                    sizePercent, 256, 256, "$label border=$ratio")
                                val clonedIcon = icon.constantState.newDrawable()
                                val clone = render(clonedIcon, 256, 256)
                                try {
                                    assertTrue("$label clone matches at $ratio", image.sameAs(clone))
                                    assertEquals("$label clone keeps actual zoom at $ratio",
                                        scales.last(), renderedGlyphScale(clonedIcon), 0f)
                                } finally { clone.recycle() }
                                exportPng(image, File(output, "$prefix-${(ratio * 100).toInt()}.png"))
                            }
                            images.forEach { assertSameOuterCoverage(images.last(), it) }
                            assertMeaningfulArtworkChange(images[0], images[1], "$label 0% to 50%")
                            assertMeaningfulArtworkChange(images[1], images[2], "$label 50% to 100%")
                            assertTrue("$label zoom grows from the normalized default toward 0%: $scales",
                                scales[0] > scales[1] && scales[1] > scales[2])
                            assertEquals("$label 50% is continuous between the endpoints",
                                (scales[0] + scales[2]) / 2f, scales[1], 0.00001f)
                            val mask = requireNotNull(result.mask)
                            val footprint = renderExteriorFootprints(mask, palette.first, palette.second,
                                roundness, 256, iconSizePercent = sizePercent)
                            try {
                                val metrics = defaultBorderMetrics(footprint.images.last(), palette.first, palette.second)
                                defaults[packageName] = metrics
                                measurements.put(JSONObject().put("package", packageName)
                                    .put("palette", paletteName).put("roundness", roundness).put("sizePercent", sizePercent)
                                    .put("source", result.source).put("actualGlyphScales", org.json.JSONArray(scales))
                                    .put("footprintGlyphScales", org.json.JSONArray(footprint.glyphScales))
                                    .put("defaultBounds", rectJson(metrics.artwork)).put("plateBounds", rectJson(metrics.plate))
                                    .put("maximumCenteredExtent", metrics.maximumCenteredExtent)
                                    .put("minimumGap", metrics.minimumGap).put("minimumGapFraction", metrics.minimumGapFraction))
                                File(output, "metadata.json").writeText(JSONObject()
                                    .put("expectedDefaultArtworkFraction", normalizedDefaultFraction)
                                    .put("measurements", measurements).toString(2))
                                assertSameArtworkZoom(footprint.glyphScales, scales, label)
                                for (index in images.indices) assertSameOuterCoverage(images[index], footprint.images[index])
                                assertNormalizedDefaultBorder(metrics, label)
                                assertPreservedSourceCenter(mask, metrics, scales[2], label)
                                assertVisibleExteriorPlateProgression(footprint.images, palette.second, "$label exterior footprint")
                                exportPng(footprint.images.last(), File(output, "$prefix-default-footprint.png"))
                                if (sizePercent == 0) smallPlateWidths[packageName] = metrics.plate.width()
                                else assertTrue("$label whole-icon size independently enlarges its plate",
                                    metrics.plate.width() > requireNotNull(smallPlateWidths[packageName]))
                            } finally { footprint.images.forEach(Bitmap::recycle) }
                        } finally { images.forEach(Bitmap::recycle) }
                    }
                    val bilibili = requireNotNull(defaults["tv.danmaku.bili"])
                    val shizi = requireNotNull(defaults["com.shizi.tool.p3"])
                    assertEquals("the two applications have the same plate bounds", shizi.plate, bilibili.plate)
                    assertEquals("default centered extent is independent of application",
                        shizi.maximumCenteredExtent, bilibili.maximumCenteredExtent, 2f)
                    assertEquals("default minimum exterior gap is independent of application",
                        shizi.minimumGap, bilibili.minimumGap, 2f)
                }
            }
            for ((packageName, result) in generated) {
                assertTrue("$packageName normalization never edits source tones or alpha",
                    requireNotNull(savedMasks[packageName]).sameAs(requireNotNull(result.mask)))
            }
        } finally {
            savedMasks.values.forEach(Bitmap::recycle)
            generated.values.forEach { it.mask?.recycle() }
        }
    }

    private fun assertInstalledRawResourceBorder(
        packageName: String,
        evidenceName: String,
        requireForegroundOnlyAdaptive: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        // Read the APK resource directly: ApplicationInfo.loadIcon/launcher copies
        // can contain the launcher's already themed adaptive layers.
        val info = packageManager.getApplicationInfo(packageName, 0)
        assertTrue("installed $evidenceName must declare its original icon resource", info.icon != 0)
        val resources = packageManager.getResourcesForApplication(info)
        val raw = resources.getDrawable(info.icon, null)
        if (requireForegroundOnlyAdaptive) {
            assertTrue("installed $evidenceName must supply its raw adaptive icon", raw is AdaptiveIconDrawable)
        }
        val generated = requireNotNull(AospMonochromeMaskStrategy(
            sourceStyle = IconSourceStyle.GENERATED,
            allowLawniconsFallback = false,
        ).generate(raw, null))
        assertTrue("installed $evidenceName uses an adjustable generated source",
            adjustsGeneratedBorder(generated.source))
        val mask = requireNotNull(generated.mask)
        val saved = requireNotNull(mask.copy(requireNotNull(mask.config), false))
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "visible-border-$evidenceName")
        assertTrue("device evidence directory is available", output.isDirectory || output.mkdirs())
        try {
            val original = render(raw, 256, 256)
            try { exportPng(original, File(output, "original.png")) }
            finally { original.recycle() }
            exportPng(mask, File(output, "mask.png"))
            if (requireForegroundOnlyAdaptive) {
                assertForegroundOnlyTonal(generated, raw as AdaptiveIconDrawable)
            }
            val metadata = JSONObject()
                .put("package", info.packageName)
                .put("iconResource", resources.getResourceName(info.icon))
                .put("input", raw.javaClass.name)
                .put("source", generated.source)
                .put("tonal", generated.tonal)
                .put("exteriorPlateMeasurement", "filled-source-footprint")
                .put("maskWidth", mask.width).put("maskHeight", mask.height)
            for ((index, palette) in palettes.withIndex()) {
                val name = if (index == 0) "light" else "dark"
                for (roundness in listOf(0, 100)) {
                    val images = mutableListOf<Bitmap>()
                    val glyphScales = mutableListOf<Float>()
                    try {
                        for (ratio in ratios) {
                            val icon = generatedDrawable(generated, palette.first, palette.second,
                                roundness, ratio)
                            val image = render(icon, 256, 256)
                            images += image
                            glyphScales += renderedGlyphScale(icon)
                            assertArtworkContained(mask, icon, roundness, 100, 256, 256,
                                "installed $evidenceName $name border=$ratio")
                            val clonedIcon = icon.constantState.newDrawable()
                            val clone = render(clonedIcon, 256, 256)
                            try {
                                assertTrue("installed $evidenceName $name clone at $ratio/$roundness", image.sameAs(clone))
                                assertEquals("installed $evidenceName clone retains actual artwork zoom at $ratio/$roundness",
                                    glyphScales.last(), renderedGlyphScale(clonedIcon), 0f)
                            }
                            finally { clone.recycle() }
                            val suffix = if (roundness == 0) "" else "-circle"
                            exportPng(image, File(output, "$name${(ratio * 100).toInt()}$suffix.png"))
                        }
                        images.forEach { assertSameOuterCoverage(images.last(), it) }
                        assertMeaningfulArtworkChange(images[0], images[1], "$evidenceName $name 0% to 50%")
                        assertMeaningfulArtworkChange(images[1], images[2], "$evidenceName $name 50% to 100%")
                        val footprintRender = renderExteriorFootprints(mask, palette.first, palette.second, roundness, 256)
                        val probes = footprintRender.images
                        try {
                            for ((index, probe) in probes.withIndex()) {
                                assertSameOuterCoverage(images[index], probe)
                                val suffix = if (roundness == 0) "" else "-circle"
                                exportPng(probe, File(output, "$name-footprint${(ratios[index] * 100).toInt()}$suffix.png"))
                            }
                            val areas = probes.map { exteriorPlateArea(it, palette.second) }
                            val opaqueArea = countOpaquePixels(probes[0])
                            val defaults = defaultBorderMetrics(probes.last(), palette.first, palette.second)
                            metadata.put("$name-$roundness-exteriorPlateAreas", org.json.JSONArray(areas))
                                .put("$name-$roundness-renderedPlateAreasIncludingEdgeConnectedHoles",
                                    org.json.JSONArray(images.map { exteriorPlateArea(it, palette.second) }))
                                .put("$name-$roundness-opaqueArea", opaqueArea)
                                .put("$name-$roundness-zeroExteriorPlateFraction", areas[0].toDouble() / opaqueArea)
                                .put("$name-$roundness-actualGlyphScales", org.json.JSONArray(glyphScales))
                                .put("$name-$roundness-footprintGlyphScales", org.json.JSONArray(footprintRender.glyphScales))
                                .put("$name-$roundness-defaultBounds", rectJson(defaults.artwork))
                                .put("$name-$roundness-plateBounds", rectJson(defaults.plate))
                                .put("$name-$roundness-maximumCenteredExtent", defaults.maximumCenteredExtent)
                                .put("$name-$roundness-minimumGapFraction", defaults.minimumGapFraction)
                            // Keep measurements available when a regression assertion fails.
                            File(output, "metadata.json").writeText(metadata.toString(2))
                            assertSameArtworkZoom(footprintRender.glyphScales, glyphScales,
                                "installed $evidenceName $name roundness=$roundness")
                            assertNormalizedDefaultBorder(defaults, "installed $evidenceName $name roundness=$roundness")
                            assertVisibleExteriorPlateProgression(probes, palette.second,
                                "installed $evidenceName $name exterior footprint roundness=$roundness")
                        } finally { probes.forEach(Bitmap::recycle) }
                    } finally { images.forEach(Bitmap::recycle) }
                }
            }
            assertTrue("device rendering never changes the extracted source mask", saved.sameAs(mask))
            File(output, "metadata.json").writeText(metadata.toString(2))
        } finally { saved.recycle(); mask.recycle() }
    }

    @Test fun smoothGradientWithEmptyForegroundKeepsItsFullTonalArtwork() {
        for (foreground in listOf<Drawable?>(null, ColorDrawable(Color.TRANSPARENT))) {
            val generated = requireNotNull(AospMonochromeMaskStrategy(
                sourceStyle = IconSourceStyle.GENERATED,
                allowLawniconsFallback = false,
            ).generate(AdaptiveIconDrawable(gradientPlate(), foreground), null))
            val mask = requireNotNull(generated.mask)
            try {
                assertEquals("background-only gradient uses the adaptive fallback", MaskStrategy.SOURCE_AOSP_ADAPTIVE, generated.source)
                assertTrue("empty foreground must not erase background tonal artwork", generated.tonal)
                assertEquals(Bitmap.Config.ARGB_8888, mask.config)
                assertEquals("background-only center remains opaque", 255,
                    Color.alpha(mask.getPixel(mask.width / 2, mask.height / 2)))
                val pixels = IntArray(mask.width * mask.height)
                mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
                val tones = pixels.filter { Color.alpha(it) == 255 }.map(Color::red)
                assertTrue("background-only gradient keeps its tonal range", tones.max() - tones.min() > 128)
            } finally { mask.recycle() }
        }
    }

    @Test fun borderEnlargesArtworkInsideTheSameOuterContourInBothPalettesAndShapes() {
        val mask = compactArtwork()
        val originalMask = requireNotNull(mask.copy(Bitmap.Config.ARGB_8888, false))
        try {
            for ((foreground, background) in palettes) for (roundness in listOf(0, 100)) {
                val images = ratios.map { ratio ->
                    render(drawable(mask, foreground, background, roundness, ratio), 200, 200)
                }
                val original = render(
                    drawable(mask, foreground, background, roundness, 1f, enabled = false), 200, 200,
                )
                try {
                    assertNormalizedDefaultBorder(defaultBorderMetrics(images.last(), foreground, background),
                        "compact artwork roundness=$roundness")
                    images.forEach { assertSameOuterCoverage(original, it) }
                    val motifAreas = images.map { countColor(it, foreground) }
                    assertTrue("less border enlarges the motif: $motifAreas", motifAreas[0] > motifAreas[1])
                    assertTrue("the intermediate motif exceeds the default: $motifAreas", motifAreas[1] > motifAreas[2])
                    assertEquals("0% retains the top of the icon", 255, Color.alpha(images[0].getPixel(100, 20)))
                    assertEquals("default has an exterior plate", background, images[2].getPixel(100, 20))
                    assertTrue("0% enlarges artwork into the previous border", images[0].getPixel(100, 36) != background)
                    for (image in images) {
                        assertCenteredSquareMotif(image, foreground)
                        assertEquals("the scaled central hole stays centered", background, image.getPixel(100, 100))
                    }
                } finally {
                    images.forEach(Bitmap::recycle)
                    original.recycle()
                }
            }
            assertTrue("rendering never edits source coverage or details", originalMask.sameAs(mask))
        } finally {
            originalMask.recycle()
            mask.recycle()
        }
    }

    @Test fun closedSquareFrameAndRingFitTheCircleWithoutFillingTheirTransparentHoles() {
        for (isRing in listOf(false, true)) {
            val label = if (isRing) "closed ring" else "closed square frame"
            val alpha = ByteArray(100 * 100).also { pixels ->
                for (y in 25 until 75) for (x in 25 until 75) {
                    val dx = x + 0.5f - 50f
                    val dy = y + 0.5f - 50f
                    val covered = if (isRing) {
                        val distanceSquared = dx * dx + dy * dy
                        distanceSquared >= 17.5f * 17.5f && distanceSquared <= 25f * 25f
                    } else {
                        x !in 34 until 66 || y !in 34 until 66
                    }
                    if (covered) pixels[y * 100 + x] = -1
                }
            }
            assertTrue("$label has sparse authored alpha before enclosing its hole",
                alpha.count { it != 0.toByte() } < 50 * 50 * 0.70f)
            val masks = listOf(
                MaskBitmapIO.create(alpha, 100, 100),
                Bitmap.createBitmap(IntArray(alpha.size) { i ->
                    if (alpha[i] == 0.toByte()) Color.TRANSPARENT else Color.WHITE
                }, 100, 100, Bitmap.Config.ARGB_8888),
            )
            try {
                for (mask in masks) {
                    val saved = requireNotNull(mask.copy(requireNotNull(mask.config), false))
                    try {
                        for ((foreground, background) in palettes) {
                            val outerWidths = mutableListOf<Int>()
                            for (size in listOf(0, 100)) {
                                val images = mutableListOf<Bitmap>()
                                try {
                                    for (ratio in ratios) {
                                        val icon = drawable(mask, foreground, background, 100, ratio, size = size)
                                        val image = render(icon, 240, 240)
                                        images += image
                                        assertArtworkContained(mask, icon, 100, size, 240, 240,
                                            "$label ${mask.config} border=$ratio size=$size")
                                        val clone = render(icon.constantState.newDrawable(), 240, 240)
                                        try { assertTrue("$label clone retains border=$ratio size=$size", image.sameAs(clone)) }
                                        finally { clone.recycle() }
                                    }
                                    images.forEach { assertSameOuterCoverage(images.last(), it) }
                                    val plate = pixelBounds(images.last()) { Color.alpha(it) >= 128 }
                                    outerWidths += plate.width()
                                    val areas = assertVisibleExteriorPlateProgression(images, background,
                                        "$label ${mask.config} size=$size")
                                    // Only exterior-connected plate pixels count here. The actual
                                    // enclosed hole must remain visible at every artwork scale.
                                    val holeAreas = images.indices.map { index ->
                                        assertEquals("$label keeps its centered transparent hole at ${ratios[index]}",
                                            background, images[index].getPixel(120, 120))
                                        countColor(images[index], background) - areas[index]
                                    }
                                    assertTrue("$label uniformly enlarges its hole with its strokes: $holeAreas",
                                        holeAreas[0] > holeAreas[1] && holeAreas[1] > holeAreas[2] && holeAreas[2] > 0)
                                } finally { images.forEach(Bitmap::recycle) }
                            }
                            assertTrue("$label only the icon size control changes the circle: $outerWidths",
                                outerWidths[0] < outerWidths[1])
                        }
                        assertEquals("$label source center stays transparent", 0,
                            Color.alpha(mask.getPixel(50, 50)))
                        assertTrue("$label rendering never fills or edits source alpha", saved.sameAs(mask))
                    } finally { saved.recycle() }
                }
            } finally { masks.forEach(Bitmap::recycle) }
        }
    }

    @Test fun largeTilesKeepGlyphProportionsAndBorderSettingThroughCloningAndBoundsChanges() {
        val mask = compactArtwork()
        try {
            for ((width, height) in listOf(320 to 160, 160 to 320)) for (ratio in ratios) {
                val drawable = drawable(mask, Color.WHITE, Color.DKGRAY, 37, ratio)
                    .forLargeIcon(width, height)
                val actual = render(drawable, width, height)
                val clone = drawable.constantState.newDrawable()
                val cloned = render(clone, width, height)
                val full = render(
                    drawable(mask, Color.WHITE, Color.DKGRAY, 37, 1f).forLargeIcon(width, height), width, height,
                )
                try {
                    assertEquals("clone retains intrinsic width", width, clone.intrinsicWidth)
                    assertEquals("clone retains intrinsic height", height, clone.intrinsicHeight)
                    assertTrue("clone retains border geometry", actual.sameAs(cloned))
                    assertSameOuterCoverage(full, actual)
                    assertCenteredSquareMotif(actual, Color.WHITE)
                    assertEquals("scaled hole stays at the tile center", Color.DKGRAY,
                        actual.getPixel(width / 2, height / 2))
                    if (ratio < 1f) {
                        assertTrue("large tile's motif grows as its border shrinks",
                            countColor(actual, Color.WHITE) > countColor(full, Color.WHITE))
                    }
                    // Launcher/folder instances are reused at different sizes.
                    val intermediate = render(drawable, height, width)
                    intermediate.recycle()
                    val restored = render(drawable, width, height)
                    try {
                        assertTrue("returning to earlier bounds restores geometry", actual.sameAs(restored))
                    } finally { restored.recycle() }
                } finally {
                    actual.recycle()
                    cloned.recycle()
                    full.recycle()
                }
            }
        } finally { mask.recycle() }
    }

    @Test fun zeroBorderRetainsDenseFrameAndOffCenterCornerArtworkAtEveryAppearance() {
        data class Artwork(val name: String, val mask: Bitmap, val markers: List<Pair<Int, Int>>, val tonal: Boolean)
        fun markedDenseArtwork(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { mask ->
            val canvas = Canvas(mask)
            val paint = Paint().apply { color = Color.GRAY }
            canvas.drawRect(12f, 12f, 88f, 88f, paint)
            paint.color = Color.BLACK
            for (x in listOf(14f, 78f)) for (y in listOf(14f, 78f)) {
                canvas.drawRect(x, y, x + 8f, y + 8f, paint)
            }
        }
        val frame = ByteArray(100 * 100) { i ->
            val x = i % 100
            val y = i / 100
            if (x in 12 until 88 && y in 12 until 88 &&
                (x !in 20 until 80 || y !in 20 until 80)) -1 else 0
        }
        val offCenter = ByteArray(100 * 100) { i ->
            if (i % 100 in 2 until 34 && i / 100 in 2 until 34) -1 else 0
        }
        val artwork = listOf(
            Artwork("dense tonal square", markedDenseArtwork(),
                listOf(18 to 18, 82 to 18, 18 to 82, 82 to 82), true),
            Artwork("closed alpha frame", MaskBitmapIO.create(frame, 100, 100),
                listOf(16 to 16, 84 to 16, 16 to 84, 84 to 84), false),
            Artwork("off-center corner logo", MaskBitmapIO.create(offCenter, 100, 100),
                listOf(6 to 6, 30 to 6, 6 to 30, 30 to 30), false),
        )
        try {
            for (sample in artwork) for (roundness in listOf(0, 50, 100)) for (size in listOf(0, 50, 100)) {
                fun icon(ratio: Float) = ColoredMonochromeDrawable(
                    sample.mask, Color.BLACK, Color.LTGRAY,
                    roundnessPercent = roundness, sizePercent = size, dynamicColors = false,
                    generatedBorderRatio = ratio, adjustGeneratedBorder = true, tonal = sample.tonal,
                )
                val reused = icon(0f)
                // Returning to the first bounds also exercises the geometry cache.
                for ((width, height) in listOf(240 to 240, 320 to 160, 160 to 320, 240 to 240)) {
                    val images = mutableListOf<Bitmap>()
                    try {
                        for (ratio in ratios) {
                            val drawable = icon(ratio)
                            val image = render(drawable, width, height)
                            images += image
                            val label = "${sample.name} r=$roundness s=$size ${width}x$height border=$ratio"
                            assertArtworkContained(sample.mask, drawable, roundness, size, width, height, label,
                                touchesPlate = ratio == 0f)
                            assertCornerMarkersVisible(image, drawable, sample.mask, sample.markers,
                                roundness, size, label)
                            if (sample.name == "closed alpha frame") {
                                assertEquals("$label preserves its enclosed hole", Color.LTGRAY,
                                    image.getPixel(width / 2, height / 2))
                            }
                            if (ratio == 0f) {
                                val clone = render(drawable.constantState.newDrawable(), width, height)
                                val rebound = render(reused, width, height)
                                try {
                                    assertTrue("$label clone retains every corner marker", image.sameAs(clone))
                                    assertTrue("$label reused bounds retain every corner marker", image.sameAs(rebound))
                                } finally { clone.recycle(); rebound.recycle() }
                            }
                        }
                        images.forEach { assertSameOuterCoverage(images.last(), it) }
                    } finally { images.forEach(Bitmap::recycle) }
                }
            }
        } finally { artwork.forEach { it.mask.recycle() } }
    }

    @Test fun wholeIconSizeIsIndependentOfBorderAmount() {
        val mask = compactArtwork()
        try {
            for (roundness in listOf(0, 100)) {
                val widths = mutableListOf<Int>()
                for (size in listOf(0, 50, 100)) {
                    val original = render(
                        drawable(mask, Color.WHITE, Color.DKGRAY, roundness, 1f, size = size), 240, 240,
                    )
                    try {
                        widths += pixelBounds(original) { Color.alpha(it) >= 128 }.width()
                        for (ratio in ratios) {
                            val image = render(
                                drawable(mask, Color.WHITE, Color.DKGRAY, roundness, ratio, size = size), 240, 240,
                            )
                            try { assertSameOuterCoverage(original, image) }
                            finally { image.recycle() }
                        }
                    } finally { original.recycle() }
                }
                assertTrue("only the size control changes outer width: $widths",
                    widths[0] < widths[1] && widths[1] < widths[2])
            }
        } finally { mask.recycle() }
    }

    @Test fun nativeAndLawniconsMasksKeepTheirOriginalPlateThroughCloning() {
        val compact = compactArtwork()
        try {
            val expected = render(drawable(compact, Color.BLACK, Color.LTGRAY, 100, 1f, enabled = false), 200, 200)
            try {
                for (source in listOf(MaskStrategy.SOURCE_NATIVE, MaskStrategy.SOURCE_LAWNICONS)) for (ratio in ratios) {
                    val original = drawable(compact, Color.BLACK, Color.LTGRAY, 100, ratio,
                        enabled = adjustsGeneratedBorder(source))
                    val image = render(original.constantState.newDrawable(), 200, 200)
                    try {
                        assertTrue("authored source $source is independent of border ratio $ratio", expected.sameAs(image))
                    } finally { image.recycle() }
                }
            } finally { expected.recycle() }
        } finally { compact.recycle() }
    }

    @Test fun allGeneratedSourcesAdjustBothAlphaAndTonalArtworkThroughCloning() {
        val artwork = compactArtwork()
        try {
            for (source in listOf(
                MaskStrategy.SOURCE_FOREGROUND,
                MaskStrategy.SOURCE_LUMINANCE,
                MaskStrategy.SOURCE_AOSP_ADAPTIVE,
                MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE,
                MaskStrategy.SOURCE_AOSP_LEGACY,
            )) for (tonal in listOf(false, true)) {
                fun icon(ratio: Float) = ColoredMonochromeDrawable(
                    artwork, Color.BLACK, Color.LTGRAY,
                    roundnessPercent = 37, sizePercent = 72, dynamicColors = false,
                    generatedBorderRatio = ratio, adjustGeneratedBorder = adjustsGeneratedBorder(source), tonal = tonal,
                ).forLargeIcon(240, 120)
                val full = render(icon(1f), 240, 120)
                val reducedIcon = icon(0f)
                val reduced = render(reducedIcon, 240, 120)
                val clone = render(reducedIcon.constantState.newDrawable(), 240, 120)
                try {
                    assertTrue("source $source adjusts artwork with tonal=$tonal", !full.sameAs(reduced))
                    assertSameOuterCoverage(full, reduced)
                    assertTrue("source $source keeps its border eligibility and tonal=$tonal when cloned",
                        reduced.sameAs(clone))
                    assertEquals("source $source preserves the artwork's center hole", Color.LTGRAY,
                        reduced.getPixel(120, 60))
                } finally {
                    full.recycle()
                    reduced.recycle()
                    clone.recycle()
                }
            }
        } finally { artwork.recycle() }
    }

    @Test fun actualGeneratedFallbackBranchesAdjustTheirRenderedArtwork() {
        val legacyBitmap = compactArtwork()
        try {
            val sparse = paintedLayer { canvas, paint ->
                paint.color = Color.BLACK
                canvas.drawRect(25f, 42f, 40f, 58f, paint)
                canvas.drawRect(60f, 42f, 75f, 58f, paint)
            }
            val tonal = paintedLayer { canvas, paint ->
                paint.color = Color.WHITE
                canvas.drawRect(25f, 25f, 75f, 75f, paint)
                paint.color = Color.BLACK
                canvas.drawRect(38f, 38f, 62f, 62f, paint)
            }
            val inputs = listOf(
                MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE to
                    AdaptiveIconDrawable(ColorDrawable(Color.WHITE), sparse),
                MaskStrategy.SOURCE_AOSP_ADAPTIVE to
                    AdaptiveIconDrawable(ColorDrawable(Color.WHITE), tonal),
                MaskStrategy.SOURCE_AOSP_LEGACY to BitmapDrawable(Resources.getSystem(), legacyBitmap),
                MaskStrategy.SOURCE_LUMINANCE to sparse,
            )
            for ((expectedSource, input) in inputs) {
                // Native preference can fall back to generated artwork. Eligibility
                // must follow the result, including the single-tone silhouette.
                val generated = requireNotNull(AospMonochromeMaskStrategy(
                    sourceStyle = IconSourceStyle.NATIVE,
                    allowLawniconsFallback = false,
                ).generate(input, null))
                val mask = requireNotNull(generated.mask)
                try {
                    assertEquals("fixture enters the real generation branch", expectedSource, generated.source)
                    val images = ratios.map { ratio ->
                        render(ColoredMonochromeDrawable(
                            mask, Color.BLACK, Color.LTGRAY,
                            roundnessPercent = 37, sizePercent = 72, dynamicColors = false,
                            generatedBorderRatio = ratio,
                            adjustGeneratedBorder = adjustsGeneratedBorder(generated.source),
                            tonal = generated.tonal,
                        ).constantState.newDrawable(), 240, 120)
                    }
                    try {
                        images.forEach { assertSameOuterCoverage(images.last(), it) }
                        val areas = images.map { image ->
                            val pixels = IntArray(image.width * image.height)
                            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                            pixels.count { Color.alpha(it) == 255 && it != Color.LTGRAY }
                        }
                        assertTrue("actual generated source $expectedSource grows as its border shrinks: $areas",
                            areas[0] > areas[1] && areas[1] > areas[2])
                    } finally { images.forEach(Bitmap::recycle) }
                } finally { mask.recycle() }
            }
        } finally { legacyBitmap.recycle() }
    }

    @Test fun actualNativeFallbackKeepsItsAuthoredBorderWhenLawniconsIsUnavailable() {
        val nativeLayer = paintedLayer { canvas, paint ->
            paint.color = Color.WHITE
            canvas.drawRect(25f, 25f, 75f, 75f, paint)
        }
        val input = AdaptiveIconDrawable(ColorDrawable(Color.WHITE), ColorDrawable(Color.BLACK), nativeLayer)
        val generated = requireNotNull(AospMonochromeMaskStrategy(
            sourceStyle = IconSourceStyle.LAWNICONS,
            allowLawniconsFallback = false,
        ).generate(input, null))
        val mask = requireNotNull(generated.mask)
        try {
            assertEquals("the selected preference falls back to real native artwork",
                MaskStrategy.SOURCE_NATIVE, generated.source)
            val images = ratios.map { ratio ->
                render(ColoredMonochromeDrawable(
                    mask, Color.BLACK, Color.LTGRAY,
                    roundnessPercent = 37, sizePercent = 72, dynamicColors = false,
                    generatedBorderRatio = ratio,
                    adjustGeneratedBorder = adjustsGeneratedBorder(generated.source),
                    tonal = generated.tonal,
                ).constantState.newDrawable(), 240, 120)
            }
            try {
                images.forEach { assertTrue("actual native fallback ignores generated border", images.last().sameAs(it)) }
            } finally { images.forEach(Bitmap::recycle) }
        } finally { mask.recycle() }
    }

    @Test fun generatedSparseAndDisconnectedMasksAdjustPaddingInBothBitmapFormats() {
        val alpha = ByteArray(100 * 100).also {
            for (y in 42 until 58) for (x in 25 until 40) {
                it[y * 100 + x] = -1
                it[y * 100 + 99 - x] = -1
            }
        }
        val alphaMask = MaskBitmapIO.create(alpha, 100, 100)
        val colorMask = Bitmap.createBitmap(IntArray(alpha.size) { i ->
            if (alpha[i] == 0.toByte()) Color.TRANSPARENT else Color.WHITE
        }, 100, 100, Bitmap.Config.ARGB_8888)
        try {
            for (mask in listOf(alphaMask, colorMask)) for (roundness in listOf(0, 100)) {
                for ((width, height) in listOf(200 to 200, 320 to 160, 160 to 320)) {
                    val images = ratios.map { ratio ->
                        render(drawable(mask, Color.BLACK, Color.LTGRAY, roundness, ratio), width, height)
                    }
                    try {
                        images.forEach { assertSameOuterCoverage(images.last(), it) }
                        val areas = images.map { countColor(it, Color.BLACK) }
                        assertTrue("less border grows disconnected artwork: $areas", areas[0] > areas[1] && areas[1] > areas[2])
                        for (image in images) {
                            assertEquals("the actual hole between motifs remains visible", Color.LTGRAY,
                                image.getPixel(width / 2, height / 2))
                        }
                    } finally { images.forEach(Bitmap::recycle) }
                }
            }
        } finally { alphaMask.recycle(); colorMask.recycle() }
    }

    @Test fun transparentPlateNeverChangesSourceHolesOrGlyphOpacity() {
        val mask = compactArtwork()
        val expected = render(drawable(mask, Color.WHITE, Color.TRANSPARENT, 100, 1f), 200, 200)
        try {
            for (ratio in ratios) {
                val image = render(drawable(mask, Color.WHITE, Color.TRANSPARENT, 100, ratio), 200, 200)
                try {
                    assertTrue("glyph-only output does not depend on border ratio", expected.sameAs(image))
                    assertEquals("true transparent hole remains transparent", 0, Color.alpha(image.getPixel(100, 100)))
                } finally { image.recycle() }
            }
        } finally {
            expected.recycle()
            mask.recycle()
        }
    }

    @Test fun drawableAlphaFadesTheCompletedIconWithoutChangingBorderOrTonalColors() {
        val mask = compactArtwork()
        try {
            for ((foreground, background) in palettes) for (tonal in listOf(false, true)) {
                for ((width, height) in listOf(200 to 200, 320 to 160)) for (roundness in listOf(0, 100)) {
                    for (ratio in ratios) {
                        val icon = ColoredMonochromeDrawable(
                            mask, foreground, background,
                            roundnessPercent = roundness, sizePercent = 100, dynamicColors = false,
                            generatedBorderRatio = ratio, adjustGeneratedBorder = true, tonal = tonal,
                        )
                        val opaque = render(icon, width, height)
                        try {
                            for (alpha in listOf(64, 128)) {
                                val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                Canvas(expected).drawBitmap(opaque, 0f, 0f, Paint().apply { this.alpha = alpha })
                                icon.alpha = alpha
                                val actual = render(icon, width, height)
                                try {
                                    assertTrue("alpha=$alpha fades the complete icon at border=$ratio, tonal=$tonal",
                                        expected.sameAs(actual))
                                } finally {
                                    expected.recycle()
                                    actual.recycle()
                                }
                            }
                        } finally { opaque.recycle() }
                    }
                }
            }
        } finally { mask.recycle() }
    }

    private fun compactArtwork(): Bitmap =
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawRect(25f, 25f, 75f, 75f, paint)
            paint.color = Color.WHITE
            canvas.drawRect(38f, 38f, 62f, 62f, paint)
            paint.color = Color.argb(160, 255, 255, 255)
            canvas.drawRect(30f, 58f, 36f, 67f, paint)
            for (y in 46 until 54) for (x in 46 until 54) bitmap.setPixel(x, y, Color.TRANSPARENT)
        }

    private fun paintedLayer(painter: (Canvas, Paint) -> Unit): Drawable = object : Drawable() {
        private val paint = Paint()
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try {
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 100f, bounds.height() / 100f)
                painter(canvas, paint)
            } finally { canvas.restoreToCount(save) }
        }
        override fun getIntrinsicWidth(): Int = 100
        override fun getIntrinsicHeight(): Int = 100
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun gradientPlate(): Drawable = paintedLayer { canvas, paint ->
        paint.shader = LinearGradient(
            0f, 0f, 100f, 100f,
            intArrayOf(Color.rgb(255, 91, 145), Color.rgb(82, 104, 255), Color.rgb(43, 220, 184)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 100f, 100f, paint)
        paint.shader = null
    }

    private fun detailedForeground(): Drawable = paintedLayer { canvas, paint ->
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(245, 66, 117)
        canvas.drawRoundRect(20f, 25f, 80f, 75f, 12f, 12f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(28f, 32f, 72f, 65f, 7f, 7f, paint)
        paint.color = Color.BLACK
        canvas.drawRect(34f, 39f, 66f, 57f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(42f, 48f, 5f, paint)
        canvas.drawCircle(58f, 48f, 5f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(42f, 48f, 2f, paint)
        canvas.drawCircle(58f, 48f, 2f, paint)
        paint.strokeWidth = 4f
        canvas.drawLine(39f, 68f, 33f, 78f, paint)
        canvas.drawLine(61f, 68f, 67f, 78f, paint)
    }

    /** Give the captured 256px layers an app-like 100-unit viewport. */
    private fun fixtureLayer(bitmap: Bitmap): BitmapDrawable =
        object : BitmapDrawable(Resources.getSystem(), bitmap) {
            override fun getIntrinsicWidth(): Int = 100
            override fun getIntrinsicHeight(): Int = 100
        }.apply { setBounds(0, 0, 100, 100) }

    private fun generatedDrawable(
        generated: com.jerrey.monoicon.mask.GenerateResult,
        foreground: Int,
        background: Int,
        roundness: Int,
        ratio: Float,
        size: Int = 100,
    ) = ColoredMonochromeDrawable(
        requireNotNull(generated.mask), foreground, background,
        roundnessPercent = roundness,
        sizePercent = size,
        dynamicColors = false,
        generatedBorderRatio = ratio,
        adjustGeneratedBorder = adjustsGeneratedBorder(generated.source),
        tonal = generated.tonal,
    )

    private fun drawable(
        mask: Bitmap,
        foreground: Int,
        background: Int,
        roundness: Int,
        ratio: Float,
        enabled: Boolean = true,
        size: Int = 100,
    ) = ColoredMonochromeDrawable(
        mask, foreground, background,
        roundnessPercent = roundness,
        sizePercent = size,
        dynamicColors = false,
        generatedBorderRatio = ratio,
        adjustGeneratedBorder = enabled,
    )

    private fun render(drawable: Drawable, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }

    private fun countColor(bitmap: Bitmap, color: Int): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it == color }
    }

    private fun assertForegroundOnlyTonal(
        generated: com.jerrey.monoicon.mask.GenerateResult,
        adaptive: AdaptiveIconDrawable,
    ) {
        val mask = requireNotNull(generated.mask)
        assertEquals("detailed foreground remains on the adaptive tonal path",
            MaskStrategy.SOURCE_AOSP_ADAPTIVE, generated.source)
        assertTrue("foreground RGB detail must survive extraction", generated.tonal)
        assertEquals(Bitmap.Config.ARGB_8888, mask.config)
        assertInsetBorderGeometry(MaskBitmapIO.read(mask), mask.width, mask.height)
        val expected = requireNotNull(AospMonochromeFactory.wrap(
            AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), adaptive.foreground), mask.width,
        ))
        try {
            assertTrue("a smooth background must not alter foreground coverage or RGB tone", expected.sameAs(mask))
        } finally { expected.recycle() }
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        assertTrue("opaque white artwork remains opaque white tone",
            pixels.any { Color.alpha(it) == 255 && Color.red(it) == 255 })
        // The darkest normalized sample can be a semitransparent raster edge;
        // the opaque interior retains a slightly higher tone after filtering.
        assertTrue("the darker body or ink remains opaque dark tone",
            pixels.any { Color.alpha(it) >= 250 && Color.red(it) <= 32 })
    }

    private fun assertVisibleBorderAcrossPalettesAndClones(
        generated: com.jerrey.monoicon.mask.GenerateResult,
        label: String,
    ) {
        for ((foreground, background) in palettes) for (roundness in listOf(0, 100)) {
            val images = mutableListOf<Bitmap>()
            val glyphScales = mutableListOf<Float>()
            try {
                for (ratio in ratios) {
                    val icon = generatedDrawable(generated, foreground, background, roundness, ratio)
                    val image = render(icon, 240, 240)
                    images += image
                    glyphScales += renderedGlyphScale(icon)
                    val clonedIcon = icon.constantState.newDrawable()
                    val clone = render(clonedIcon, 240, 240)
                    try {
                        assertTrue("$label clone keeps visible border at $ratio/$roundness", image.sameAs(clone))
                        assertEquals("$label clone retains actual artwork zoom at $ratio/$roundness",
                            glyphScales.last(), renderedGlyphScale(clonedIcon), 0f)
                    }
                    finally { clone.recycle() }
                }
                images.forEach { assertSameOuterCoverage(images.last(), it) }
                assertMeaningfulArtworkChange(images[0], images[1], "$label 0% to 50%")
                assertMeaningfulArtworkChange(images[1], images[2], "$label 50% to 100%")
                val footprintRender = renderExteriorFootprints(requireNotNull(generated.mask), foreground, background,
                    roundness, 240)
                val probes = footprintRender.images
                try {
                    assertSameArtworkZoom(footprintRender.glyphScales, glyphScales, "$label roundness=$roundness")
                    for (index in images.indices) assertSameOuterCoverage(images[index], probes[index])
                    assertVisibleExteriorPlateProgression(probes, background,
                        "$label exterior footprint roundness=$roundness")
                } finally { probes.forEach(Bitmap::recycle) }
            } finally { images.forEach(Bitmap::recycle) }
        }
    }

    // Inspect the zoom already used by the real Android draw call; containment
    // below verifies that transform against every occupied source pixel corner.
    private fun renderedGlyphScale(icon: Drawable): Float = generatedGlyphScaleField.getFloat(icon)

    /** Check the actual draw transform against rounded-rectangle geometry, without computing a fit scale. */
    private fun assertArtworkContained(
        mask: Bitmap,
        icon: Drawable,
        roundness: Int,
        size: Int,
        width: Int,
        height: Int,
        label: String,
        touchesPlate: Boolean = false,
    ) {
        val plate = RectF(IconAppearance(roundness, size).contentBounds(Rect(0, 0, width, height)))
        val radius = minOf(plate.width(), plate.height()) * (0.25f + roundness * 0.0025f)
        val scale = minOf(plate.width() / mask.width, plate.height() / mask.height) * renderedGlyphScale(icon)
        val alpha = MaskBitmapIO.read(mask)
        var minimumClearance = Float.POSITIVE_INFINITY
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (alpha[y * mask.width + x] == 0.toByte()) continue
            for (edgeX in x..x + 1) for (edgeY in y..y + 1) {
                val dx = kotlin.math.abs((edgeX - mask.width / 2f) * scale)
                val dy = kotlin.math.abs((edgeY - mask.height / 2f) * scale)
                val qx = dx - (plate.width() / 2f - radius)
                val qy = dy - (plate.height() / 2f - radius)
                val cornerX = maxOf(qx, 0f)
                val cornerY = maxOf(qy, 0f)
                val signedDistance = kotlin.math.sqrt(cornerX * cornerX + cornerY * cornerY) +
                    minOf(maxOf(qx, qy), 0f) - radius
                minimumClearance = minOf(minimumClearance,
                    -signedDistance)
                assertTrue("$label retains source corner ($edgeX,$edgeY) within its rounded plate",
                    signedDistance <= 0.01f)
            }
        }
        if (touchesPlate) {
            assertTrue("$label removes available padding until an occupied edge touches the plate",
                minimumClearance <= 0.01f)
        }
    }

    /** Opaque marker interiors must survive the render, including corners previously removed by cover zoom. */
    private fun assertCornerMarkersVisible(
        image: Bitmap,
        icon: Drawable,
        mask: Bitmap,
        markers: List<Pair<Int, Int>>,
        roundness: Int,
        size: Int,
        label: String,
    ) {
        val plate = IconAppearance(roundness, size).contentBounds(Rect(0, 0, image.width, image.height))
        val scale = minOf(plate.width().toFloat() / mask.width, plate.height().toFloat() / mask.height) *
            renderedGlyphScale(icon)
        for ((sourceX, sourceY) in markers) {
            val x = Math.round(plate.exactCenterX() + (sourceX - mask.width / 2f) * scale)
            val y = Math.round(plate.exactCenterY() + (sourceY - mask.height / 2f) * scale)
            assertTrue("$label marker ($sourceX,$sourceY) remains on the canvas",
                x in 1 until image.width - 1 && y in 1 until image.height - 1)
            for (sampleY in y - 1..y + 1) for (sampleX in x - 1..x + 1) {
                assertEquals("$label retains opaque corner marker ($sourceX,$sourceY)",
                    Color.BLACK, image.getPixel(sampleX, sampleY))
            }
        }
    }

    private fun assertSameArtworkZoom(expected: List<Float>, actual: List<Float>, label: String) {
        assertEquals("$label measures every border setting", ratios.size, actual.size)
        for (index in ratios.indices) {
            assertEquals("$label actual artwork uses exterior fit zoom at ${ratios[index]}",
                expected[index], actual[index], 0f)
        }
    }

    private data class ExteriorFootprintRender(val images: List<Bitmap>, val glyphScales: List<Float>)

    private data class DefaultBorderMetrics(
        val plate: Rect,
        val artwork: Rect,
        val maximumCenteredExtent: Float,
        val minimumGap: Float,
        val minimumGapFraction: Float,
    )

    private fun defaultBorderMetrics(image: Bitmap, foreground: Int, background: Int): DefaultBorderMetrics {
        val contrast = kotlin.math.abs(Color.red(foreground) - Color.red(background))
        require(contrast > 0) { "border measurements need contrasting red channels" }
        val plate = pixelBounds(image) { Color.alpha(it) >= 128 }
        // The opaque plate hides source alpha. Recover >=32 coverage from the
        // solid silhouette's foreground/background mixture, including raster edges.
        val artwork = pixelBounds(image) {
            Color.alpha(it) >= 128 &&
                kotlin.math.abs(Color.red(it) - Color.red(background)) * 255 >= contrast * 32
        }
        assertTrue("default artwork has visible source coverage", !artwork.isEmpty)
        val centeredExtent = 2f * maxOf(
            plate.exactCenterX() - artwork.left, artwork.right - plate.exactCenterX(),
            plate.exactCenterY() - artwork.top, artwork.bottom - plate.exactCenterY(),
        )
        val minimumGap = minOf(artwork.left - plate.left, plate.right - artwork.right,
            artwork.top - plate.top, plate.bottom - artwork.bottom).toFloat()
        return DefaultBorderMetrics(plate, artwork, centeredExtent, minimumGap,
            minimumGap / minOf(plate.width(), plate.height()))
    }

    private fun assertNormalizedDefaultBorder(metrics: DefaultBorderMetrics, label: String) {
        val shortSide = minOf(metrics.plate.width(), metrics.plate.height()).toFloat()
        assertEquals("$label default reserves the common centered exterior extent",
            normalizedDefaultFraction * shortSide, metrics.maximumCenteredExtent, 2f)
        assertEquals("$label default reserves the common minimum exterior gap",
            (1f - normalizedDefaultFraction) * shortSide / 2f, metrics.minimumGap, 2f)
    }

    private fun assertPreservedSourceCenter(mask: Bitmap, metrics: DefaultBorderMetrics, glyphScale: Float, label: String) {
        val source = pixelBounds(mask) { Color.alpha(it) >= 32 }
        val scale = minOf(metrics.plate.width().toFloat() / mask.width,
            metrics.plate.height().toFloat() / mask.height) * glyphScale
        assertEquals("$label retains the source's horizontal offset",
            metrics.plate.exactCenterX() + (source.exactCenterX() - mask.width / 2f) * scale,
            metrics.artwork.exactCenterX(), 1.5f)
        assertEquals("$label retains the source's vertical offset",
            metrics.plate.exactCenterY() + (source.exactCenterY() - mask.height / 2f) * scale,
            metrics.artwork.exactCenterY(), 1.5f)
    }

    private fun rectJson(rect: Rect) = org.json.JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))

    /**
     * Internal source holes may meet the tile edge after zooming. A separate,
     * solid exterior silhouette measures padding without treating those holes
     * as a border; the real source mask and all exported artwork stay intact.
     */
    private fun renderExteriorFootprints(
        mask: Bitmap,
        foreground: Int,
        background: Int,
        roundness: Int,
        size: Int,
        iconSizePercent: Int = 100,
    ): ExteriorFootprintRender {
        val alpha = MaskBitmapIO.read(mask)
        val width = mask.width
        val height = mask.height
        val maximumAlpha = alpha.maxOf { it.toInt() and 255 }
        require(maximumAlpha > 0) { "exterior footprint requires visible artwork" }
        val threshold = minOf(32, maximumAlpha)
        val outside = BooleanArray(alpha.size)
        val queue = IntArray(alpha.size)
        var head = 0
        var tail = 0
        fun visit(index: Int) {
            if (!outside[index] && (alpha[index].toInt() and 255) < threshold) {
                outside[index] = true
                queue[tail++] = index
            }
        }
        for (x in 0 until width) { visit(x); visit((height - 1) * width + x) }
        for (y in 0 until height) { visit(y * width); visit(y * width + width - 1) }
        while (head < tail) {
            val index = queue[head++]
            if (index % width > 0) visit(index - 1)
            if (index % width + 1 < width) visit(index + 1)
            if (index >= width) visit(index - width)
            if (index + width < alpha.size) visit(index + width)
        }
        // Fill the visible silhouette's enclosed holes, while preserving every
        // exterior fringe byte for nonzero containment and the >=32 baseline.
        val footprint = MaskBitmapIO.create(ByteArray(alpha.size) {
            if (!outside[it]) 255.toByte() else alpha[it]
        }, width, height)
        val images = mutableListOf<Bitmap>()
        val glyphScales = mutableListOf<Float>()
        try {
            for (ratio in ratios) {
                val icon = drawable(footprint, foreground, background, roundness, ratio, size = iconSizePercent)
                images += render(icon, size, size)
                glyphScales += renderedGlyphScale(icon)
            }
            return ExteriorFootprintRender(images, glyphScales)
        } catch (error: Throwable) {
            images.forEach(Bitmap::recycle)
            throw error
        } finally { footprint.recycle() }
    }

    /** Exact plate color connected to the tile exterior excludes enclosed holes. */
    private fun exteriorPlateArea(image: Bitmap, background: Int): Int {
        val width = image.width
        val height = image.height
        val pixels = IntArray(width * height)
        image.getPixels(pixels, 0, width, 0, 0, width, height)
        val visited = BooleanArray(pixels.size)
        val queue = IntArray(pixels.size)
        var head = 0
        var tail = 0
        fun visit(i: Int) {
            if (!visited[i] && pixels[i] == background && Color.alpha(pixels[i]) == 255) {
                visited[i] = true
                queue[tail++] = i
            }
        }
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1 ||
                (x > 0 && Color.alpha(pixels[i - 1]) < 255) ||
                (x + 1 < width && Color.alpha(pixels[i + 1]) < 255) ||
                (y > 0 && Color.alpha(pixels[i - width]) < 255) ||
                (y + 1 < height && Color.alpha(pixels[i + width]) < 255)
            ) visit(i)
        }
        while (head < tail) {
            val i = queue[head++]
            if (i % width > 0) visit(i - 1)
            if (i % width + 1 < width) visit(i + 1)
            if (i >= width) visit(i - width)
            if (i + width < pixels.size) visit(i + width)
        }
        return tail
    }

    private fun assertVisibleExteriorPlateProgression(
        images: List<Bitmap>,
        background: Int,
        label: String,
    ): List<Int> {
        assertEquals("measure 0%, 50%, 100% in order", 3, images.size)
        val areas = images.map { exteriorPlateArea(it, background) }
        val opaqueArea = countOpaquePixels(images.last())
        assertTrue("$label 100% visibly exposes the outer plate: $areas",
            areas[2] > opaqueArea / 20)
        assertTrue("$label exterior plate grows from 0% to 50% to 100%: $areas",
            areas[0] < areas[1] && areas[1] < areas[2])
        // Measure the exterior separately from enclosed transparent holes,
        // which grow with the intact frame and its artwork scale.
        assertMeaningfulArtworkChange(images[0], images[1], "$label 0% to 50%")
        assertMeaningfulArtworkChange(images[1], images[2], "$label 50% to 100%")
        return areas
    }

    private fun countOpaquePixels(image: Bitmap): Int {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        return pixels.count { Color.alpha(it) == 255 }
    }

    private fun exportPng(image: Bitmap, file: File) {
        file.outputStream().use { assertTrue("write device evidence ${file.name}", image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun assertInsetBorderGeometry(alpha: ByteArray, width: Int, height: Int) {
        assertEquals("border alpha matches mask dimensions", width * height, alpha.size)
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        for (y in 0 until height) for (x in 0 until width) {
            if ((alpha[y * width + x].toInt() and 0xFF) == 0) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
        assertTrue("foreground border geometry is non-empty", right > left && bottom > top)
        assertTrue("foreground geometry excludes the opaque plate horizontally", left > 0 && right < width)
        assertTrue("foreground geometry excludes the opaque plate vertically", top > 0 && bottom < height)
    }

    private fun assertMeaningfulArtworkChange(first: Bitmap, second: Bitmap, label: String) {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        var changed = 0
        for (y in 0 until first.height) for (x in 0 until first.width) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        }
        assertTrue("$label changes rendered body pixels: $changed",
            changed > first.width * first.height / 100)
    }

    private fun assertSameOuterCoverage(expected: Bitmap, actual: Bitmap) {
        assertEquals("outer width", expected.width, actual.width)
        assertEquals("outer height", expected.height, actual.height)
        assertEquals("visible bounds", pixelBounds(expected) { Color.alpha(it) >= 128 },
            pixelBounds(actual) { Color.alpha(it) >= 128 })
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            assertEquals("border amount preserves outer alpha at $x,$y",
                Color.alpha(expected.getPixel(x, y)), Color.alpha(actual.getPixel(x, y)))
        }
    }

    private fun assertCenteredSquareMotif(image: Bitmap, foreground: Int) {
        val motif = pixelBounds(image) { it == foreground }
        assertTrue("opaque motif exists", !motif.isEmpty)
        assertEquals("uniform scale preserves motif proportions", motif.width().toFloat(), motif.height().toFloat(), 2f)
        assertEquals("motif stays horizontally centered", image.width / 2f, motif.exactCenterX(), 1f)
        assertEquals("motif stays vertically centered", image.height / 2f, motif.exactCenterY(), 1f)
    }

    private fun pixelBounds(bitmap: Bitmap, contains: (Int) -> Boolean): Rect {
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

    private val ratios = listOf(0f, 0.5f, 1f)
    // Legacy square reference: 0.7 * sqrt(375 / 576), independent of source viewport.
    private val normalizedDefaultFraction = 0.56481f
    private val generatedGlyphScaleField by lazy {
        ColoredMonochromeDrawable::class.java.getDeclaredField("generatedGlyphScale").apply { isAccessible = true }
    }
    private val palettes = listOf(Color.rgb(44, 32, 49) to Color.rgb(246, 218, 253),
        Color.rgb(236, 213, 242) to Color.rgb(53, 38, 60))
}
