// Preview card styling follows KernelSU ColorPaletteScreenMiuix, GPL-3.0-or-later.
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.color.dynamic.IconColorEngineId
import com.jerrey.monoicon.theme.color.dynamic.IconThemeColors
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.theme.color.dynamic.MaterialIconColorEngine
import com.jerrey.monoicon.theme.mask.MaskStrategy
import com.jerrey.monoicon.theme.render.adjustsGeneratedBorder
import com.jerrey.monoicon.theme.wallpaper.WallpaperSnapshot
import com.jerrey.monoicon.theme.wallpaper.WallpaperProvenance
import com.jerrey.monoicon.theme.wallpaper.WallpaperSourceRepository
import com.jerrey.monoicon.theme.wallpaper.WallpaperStatus
import com.jerrey.monoicon.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private data class PreviewSourceKey(
    val style: IconSourceStyle,
    val lawniconsEnabled: Boolean,
    val allowLawniconsFallback: Boolean,
    val revision: Int,
    val reload: Int,
    val identity: String?,
    val singleIcon: Boolean,
    val generatedBorderOnly: Boolean,
)
private data class RenderedStylePreview(
    val examples: List<IconStyleExample>,
    val icons: List<ImageBitmap>,
    val sourceToken: Any,
)
private data class PreviewRegion(val left: Float, val top: Float, val displayWidth: Int, val displayHeight: Int)

/** The live settings values are passed explicitly; rendering never writes preferences. */
@Composable
fun IconStylePreview(
    roundnessPercent: Int,
    sizePercent: Int,
    colorEngineId: String,
    variantId: String,
    lawniconsEnabled: Boolean,
    configRevision: Int,
    modifier: Modifier = Modifier,
    generatedIconBorderRatio: Float = 1f,
    wallpaperSnapshot: WallpaperSnapshot? = null,
    loadExamples: (suspend (Context, Boolean) -> List<IconStyleExample>)? = null,
    renderExamples: (suspend (List<IconStyleExample>, IconThemeColors, Int, Int, Float) -> List<Bitmap>)? = null,
    sourceStyle: IconSourceStyle = if (lawniconsEnabled) IconSourceStyle.LAWNICONS else IconSourceStyle.NATIVE_GENERATED,
    allowLawniconsFallback: Boolean = true,
    previewKey: String? = null,
    singleIcon: Boolean = false,
    generatedBorderOnly: Boolean = false,
    generatedArtworkRadiusFraction: Float = 0f,
) {
    val strings = LocalStrings.current
    val preferenceStrings = rememberIconPreferencesStrings()
    val context = LocalContext.current
    val view = LocalView.current
    val repositorySnapshot by WallpaperSourceRepository.state.collectAsState()
    val wallpaper = wallpaperSnapshot ?: repositorySnapshot
    val dark = isSystemInDarkTheme()
    val colors = remember(wallpaper.seedColor, dark, colorEngineId, variantId) {
        MaterialIconColorEngine.iconColorsForSource(
            wallpaper.seedColor, dark, Material2025ColorEngine.variantFromId(variantId),
            IconColorEngineId.fromId(colorEngineId),
        )
    }
    var reload by remember { mutableIntStateOf(0) }
    val sourceKey = PreviewSourceKey(sourceStyle, lawniconsEnabled, allowLawniconsFallback,
        configRevision, reload, previewKey, singleIcon, generatedBorderOnly)
    // Each visit gets its own token: A -> B -> A must not revive A's old frame.
    val sourceToken = remember(context, sourceKey) { Any() }
    var examples by remember(context, sourceKey) { mutableStateOf<List<IconStyleExample>>(emptyList()) }
    var loading by remember(context, sourceKey) { mutableStateOf(true) }
    var failed by remember(context, sourceKey) { mutableStateOf(false) }
    var region by remember { mutableStateOf<PreviewRegion?>(null) }
    LaunchedEffect(context, wallpaperSnapshot == null) {
        if (wallpaperSnapshot == null) WallpaperSourceRepository.init(context)
    }
    LaunchedEffect(context, sourceKey) {
        try {
            val loaded = loadExamples?.invoke(context, lawniconsEnabled)
                ?: IconStylePreviewRepository.loadExamples(context, sourceStyle, allowLawniconsFallback,
                    generatedBorderOnly)
            currentCoroutineContext().ensureActive()
            val eligible = if (generatedBorderOnly) loaded.filter {
                !it.lawnicons && !it.bundledSample && !it.identity.startsWith("framework:") &&
                    adjustsGeneratedBorder(it.source)
            } else loaded
            examples = if (singleIcon) eligible.take(1) else eligible
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    // Retain the last completed frame while a new one renders. Original sources
    // stay loaded across slider updates; cancellation cannot publish an obsolete frame.
    val rendered by produceState<RenderedStylePreview?>(null, sourceToken, examples, colors, roundnessPercent,
        sizePercent, generatedIconBorderRatio, generatedArtworkRadiusFraction) {
        if (examples.isEmpty()) {
            value = null
            return@produceState
        }
        try {
            val bitmaps = renderExamples?.invoke(examples, colors, roundnessPercent, sizePercent, generatedIconBorderRatio)
                ?: IconStylePreviewRepository.renderExamples(examples, colors, roundnessPercent, sizePercent,
                    generatedIconBorderRatio, generatedArtworkRadiusFraction)
            currentCoroutineContext().ensureActive()
            value = RenderedStylePreview(examples, bitmaps.map { it.asImageBitmap() }, sourceToken)
            failed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val actualWallpaper = wallpaper.bitmap?.takeIf {
        wallpaper.provenance != WallpaperProvenance.STABLE_FALLBACK
    }
    val wallpaperPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }

    Column(
        modifier = modifier.testTag("icon-style-preview").semantics {
            val borderDescription = if (examples.any { !it.lawnicons && adjustsGeneratedBorder(it.source) })
                ", " + strings.iconBorderSize + ": ${(generatedIconBorderRatio * 100f).roundToInt()}%" else ""
            val artworkDescription = if (borderDescription.isNotEmpty())
                ", " + preferenceStrings.artworkRoundness + ": ${(generatedArtworkRadiusFraction * 200f).roundToInt()}%" else ""
            stateDescription = strings.iconRoundness + ": $roundnessPercent%, " +
                strings.iconSize + ": $sizePercent%" + borderDescription + artworkDescription
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = strings.iconPreview,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Card(modifier = Modifier.fillMaxWidth().animateContentSize(
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)),
            insideMargin = PaddingValues(0.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    val windowLocation = IntArray(2)
                    view.rootView.getLocationOnScreen(windowLocation)
                    val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
                    region = PreviewRegion(
                        position.x + windowLocation[0] - bounds.left,
                        position.y + windowLocation[1] - bounds.top,
                        bounds.width(), bounds.height(),
                    )
                },
            ) {
                if (actualWallpaper != null && region != null) {
                    val viewport = region!!
                    Canvas(Modifier.matchParentSize().testTag("icon-preview-wallpaper")) {
                        val projection = wallpaperPreviewProjection(
                            actualWallpaper.width, actualWallpaper.height,
                            viewport.displayWidth, viewport.displayHeight, viewport.left, viewport.top,
                        )
                        drawContext.canvas.nativeCanvas.drawBitmap(actualWallpaper, null,
                            RectF(projection.left, projection.top, projection.right, projection.bottom), wallpaperPaint)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Changing apps or sources must never keep an obsolete frame on screen.
                    // Geometry updates retain their completed frame and are not crossfaded.
                    val preview = rendered?.takeIf { it.sourceToken === sourceToken }
                    Crossfade(targetState = preview != null, animationSpec = tween(180), label = "preview-ready") { ready ->
                    if (!ready || preview == null) {
                        Box(Modifier.fillMaxWidth().height(if (singleIcon) 90.dp else 180.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = when {
                                    failed -> strings.iconPreviewError
                                    !loading && examples.isEmpty() -> strings.iconPreviewEmpty
                                    else -> strings.iconPreviewLoading
                                },
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (actualWallpaper != null) Color.White else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        preview.examples.zip(preview.icons).chunked(3).forEachIndexed { rowIndex, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEachIndexed { columnIndex, (example, icon) ->
                                    val label = if (example.bundledSample) strings.iconPreviewLawniconsSample else example.label
                                    val sourceDescription = when {
                                        example.lawnicons -> strings.lawniconsPreview
                                        example.source == MaskStrategy.SOURCE_NATIVE -> preferenceStrings.nativeStyle + " · " + strings.iconPreview
                                        else -> strings.monoiconPreview
                                    }
                                    val description = (label ?: (strings.iconPreview + " " + (rowIndex * 3 + columnIndex + 1))) +
                                        ", " + sourceDescription
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Image(icon, description, Modifier.size(64.dp).testTag("icon-preview-${example.identity}"))
                                        Text(
                                            text = label.orEmpty(),
                                            style = MiuixTheme.textStyles.footnote1.copy(
                                                shadow = if (actualWallpaper != null) Shadow(Color.Black, Offset(0f, 1f), 3f) else null,
                                            ),
                                            color = if (actualWallpaper != null) Color.White else MiuixTheme.colorScheme.onSurface,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
                                        )
                                    }
                                }
                                if (!singleIcon) repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                        }
                    }
                    }
                }
            }
        }
        if (failed) {
            TextButton(
                text = strings.retry,
                onClick = { reload++ },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (!loading && wallpaper.status == WallpaperStatus.UNAVAILABLE) {
            Text(strings.iconPreviewUnavailable, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
        } else if (wallpaper.status == WallpaperStatus.CACHED) {
            Text(strings.iconPreviewCached, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}
