// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
// Ported from the Miuix example's `component/liquid/LiquidGlassNavigationBar.kt`
// (the `IosLiquidGlassNavigationBar` composable) —
// https://github.com/compose-miuix-ui/miuix — itself adapted from
// Kyant0/AndroidLiquidGlass (Apache 2.0).
//
// This is example-side UI, NOT a Miuix public component. Differences from the
// original, and why:
//  - takes a plain `List<NavigationItem>` instead of the example's `items`;
//  - colours come from `MiuixTheme.colorScheme` (so the bar rides the same
//    dynamic-colour scheme as the rest of the UI) rather than a local
//    `isInDarkTheme()` helper;
//  - the example's `InnerShadow` modifier is not ported: it only decorates the
//    selection indicator while a press is in progress, which the interactive
//    highlight already covers. Dropping it removes ~120 lines of custom
//    RenderEffect/graphics-layer plumbing.
//
// Kept faithfully: the three-layer structure (glass surface + recorded tab
// layer + sliding indicator), the drag-to-select damping, the press-follow
// spotlight, and the lens refraction / vibrancy / chromatic aberration on both
// the surface and the indicator.

package com.jerrey.monoicon.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/** Drawn height of the glass pill. */
val LiquidGlassBarHeight = 64.dp

/**
 * A plain `SrcOver` rim stroke for the pill.
 *
 * Drawn as part of the node's own content, so it participates in the press-scale
 * `layerBlock` and grows with the pill. Avoids Miuix's `Highlight` here: its
 * `BloomStroke` composites with `BlendMode.Plus`, which does nothing over a light
 * backdrop, leaving only the shader's dark coverage visible as a black rim.
 */
private fun pillRim(shape: Shape, alpha: Float): Modifier = Modifier.drawWithContent {
    drawContent()
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    drawPath(
        path = path,
        color = Color.White.copy(alpha = alpha),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** Gap between the pill and the bottom of the area the Scaffold handed us. */
val LiquidGlassBarVisualGap = 12.dp

/**
 * How far the selection pill (and, together with it, the tab content) expands
 * while pressed. 78/56 is the reference implementation's ratio and the value the
 * rest of the press physics (`DampedDragAnimation.pressedScale`) is tuned for, so
 * both must stay in step.
 *
 * It is deliberately tied to [LiquidGlassBarPillHeight]: the pill's content box is
 * 56dp inside a 64dp bar, and the bar itself does NOT scale, so this factor has to
 * stay inside the bar instead of spilling past it.
 */
const val PressScale = 78f / 56f

/** Content height of the selection pill inside [LiquidGlassBarHeight]. */
val LiquidGlassBarPillHeight = 56.dp

private val LocalIosTabScale = staticCompositionLocalOf { { 1f } }

private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)


/**
 * iOS-style liquid-glass navigation bar: a floating glass pill with drag-to-select
 * tabs, a press-follow spotlight, and a sliding indicator pill that samples the
 * bar (with lens refraction and chromatic aberration) rather than flat tinting.
 *
 * @param items tab icons + labels. Labels are also the accessibility names.
 * @param selectedIndex currently selected tab.
 * @param onItemClick invoked on tap or drag-release onto a different tab.
 * @param backdrop the recorded page content. `null` disables glass (opaque fallback).
 * @param isBlurActive gate for the glass path; `false` renders a flat bar.
 */
@Composable
fun LiquidGlassNavigationBar(
    items: List<NavigationItem>,
    selectedIndex: Int,
    onItemClick: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    isBlurActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val pillShape = remember { CircleShape }
    val accentColor = MiuixTheme.colorScheme.primary
    val tabContentColor = MiuixTheme.colorScheme.onSurface
    val surfaceContainer = MiuixTheme.colorScheme.surfaceContainerHighest
    // The fill's ALPHA is what drives the rendered tone here, and on device the
    // relationship is steep and non-linear: this role measured #505054 at a=0.3,
    // #83848A at 0.55, #C3C4CD at 0.85 and #CDCFD8 at 0.9 over a #EEEEF7 page. Low
    // alphas do NOT read as "more transparent glass" — they read as a dark plate.
    // So the fill is deliberately near-opaque and the frosted look comes from the
    // blur/refraction layered underneath it.
    //
    // Direction is per-theme. On a light page the container role is darker than the
    // background, so tinting toward it reads as a recessed bar. The dark scheme goes
    // the other way: there the role sits barely above the page, so tinting toward it
    // left the selected pill invisible (measured #24262D on a #15181D page). Dark
    // theme therefore tints toward white instead.
    val containerColor = when {
        !isBlurActive -> surfaceContainer
        isDark -> Color.White.copy(alpha = 0.15f)
        else -> surfaceContainer.copy(alpha = 0.9f)
    }
    // Resting rim light. Without it a flat backdrop leaves the pill with no edge
    // definition whatsoever; the specular edge is what makes glass read as glass
    // when there is no content behind it to refract. The near-opaque light-theme
    // plate needs only a hint of edge; the dark theme's lightly-lifted fill leans on
    // it for definition, so it gets the brighter preset.
    val restingHighlight = if (isDark) {
        Highlight.GlassStrokeMiddleLight.copy(alpha = 0.7f)
    } else {
        Highlight.GlassStrokeMiddleDark.copy(alpha = 0.35f)
    }
    // Very soft top edge so the rim has something to sit against; a glass surface
    // catches a little more light on the side facing the (assumed overhead) light.
    val surfaceSheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.05f else 0.16f),
            Color.Transparent,
        ),
    )

    // Selection is expressed by the tinted pill behind the icon and label rather
    // than by recolouring the label itself. The label colour is not mixed toward
    // `onPrimaryContainer` because that role is tuned against an opaque container:
    // the pill here sits over glass, so at these alphas it has far less contrast
    // than the role assumes and the label would lose legibility.
    val selectedFill = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val tabsCount = items.size

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val rubberBandPx = with(density) { 4.dp.toPx() }
    val panelOffset by remember(rubberBandPx) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).coerceIn(-1f, 1f)
                rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
            }
        }
    }

    var currentIndex by remember { mutableIntStateOf(selectedIndex) }
    val onItemClickUpdated by rememberUpdatedState(onItemClick)

    fun indexAt(positionX: Float): Int {
        if (tabWidthPx == 0f) return currentIndex
        val horizontalPaddingPx = with(density) { 4.dp.toPx() }
        val logicalX = if (isLtr) positionX else totalWidthPx - positionX
        return ((logicalX - horizontalPaddingPx) / tabWidthPx)
            .toInt()
            .coerceIn(0, tabsCount - 1)
    }

    val dampedDrag = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex.toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = PressScale,
            canDrag = { position ->
                position.x in 0f..totalWidthPx
            },
            onDragStarted = { position ->
                updateValue(indexAt(position.x).toFloat())
            },
            onDragStopped = {
                val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                if (currentIndex != targetIndex) {
                    currentIndex = targetIndex
                    onItemClickUpdated(targetIndex)
                }
                updateValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDragCancelled = {
                updateValue(currentIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0f && dragAmount.x != 0f) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .coerceIn(0f, (tabsCount - 1).toFloat()),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            },
        )
    }

    LaunchedEffect(selectedIndex) {
        if (currentIndex != selectedIndex) {
            currentIndex = selectedIndex
            dampedDrag.animateToValue(selectedIndex.toFloat())
        }
    }

    fun activateTab(index: Int) {
        if (index !in 0 until tabsCount) return
        if (currentIndex != index) {
            currentIndex = index
            onItemClickUpdated(index)
        }
        dampedDrag.animateToValue(index.toFloat())
    }

    // Keyed on dampedDrag: the position lambda captures it; a stale capture would freeze the press spot.
    val interactiveHighlight = remember(animationScope, isLtr, dampedDrag) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { layerSize, _ ->
                Offset(
                    x = if (isLtr) {
                        (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    } else {
                        layerSize.width - (dampedDrag.value + 0.5f) * tabWidthPx + panelOffset
                    },
                    y = layerSize.height / 2f,
                )
            },
        )
    }

    // The bar's specular is a constant: the indicator no longer uses one, so the
    // gravity-tilt rotation that existed only to drive its highlight is gone.
    val baseHighlight = iosIndicatorSpecular

    val combinedBackdrop = backdrop?.let { rememberCombinedBackdrop(it, tabsBackdrop) }

    // ONE press transform for the movable group. The selection pill and the tab
    // content are separate nodes but both read this same factor, so they scale
    // together and cannot produce a double image.
    val pressScale: () -> Float = { lerp(1f, PressScale, dampedDrag.pressProgress) }

    val tabsContent: @Composable RowScope.() -> Unit = {
        val tabScale = LocalIosTabScale.current
        items.forEachIndexed { index, item ->
            Column(
                modifier = Modifier
                    .semantics(mergeDescendants = true) {
                        selected = index == currentIndex
                        role = Role.Tab
                        onClick {
                            activateTab(index)
                            true
                        }
                    }
                    .onKeyEvent { event ->
                        val isActivationKey = event.key == Key.Enter ||
                            event.key == Key.NumPadEnter || event.key == Key.Spacebar
                        if (isActivationKey) {
                            if (event.type == KeyEventType.KeyUp) activateTab(index)
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .weight(1f)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val s = tabScale()
                        scaleX = s
                        scaleY = s
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                horizontalAlignment = CenterHorizontally,
            ) {
                Icon(
                    modifier = Modifier.size(22.dp),
                    imageVector = item.icon,
                    // Decorative: the adjacent label names the item; avoids TalkBack double-read.
                    contentDescription = null,
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(bottom = LiquidGlassBarVisualGap, start = 24.dp, end = 24.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            // ── Row A: the glass surface. It never scales. ────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .dropShadow(
                        shape = pillShape,
                        shadow = Shadow(
                            radius = 10.dp,
                            color = Color.Black,
                            // Lighter in light theme to avoid a visible gray fringe.
                            alpha = if (isDark) 0.2f else 0.1f,
                        ),
                    )
                    .then(
                        if (isBlurActive && backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { pillShape },
                                effects = {
                                    // 24dp lens refraction + press-scale reach, raised before blur() reads it.
                                    padding = maxOf(padding, 40.dp.toPx())
                                    vibrancy()
                                    blur(4.dp.toPx(), 4.dp.toPx())
                                    lens(
                                        refractionHeight = 24.dp.toPx(),
                                        refractionAmount = 24.dp.toPx(),
                                    )
                                },
                                // Constant rim light, brightening while pressed. This is
                                // what gives the pill an edge when the backdrop is flat.
                                highlight = {
                                    val press = dampedDrag.pressProgress
                                    baseHighlight.copy(
                                        alpha = restingHighlight.alpha + (0.75f - restingHighlight.alpha) * press,
                                    )
                                },
                                onDrawSurface = {
                                    drawRect(containerColor)
                                    drawRect(brush = surfaceSheen)
                                },
                            )
                        } else {
                            Modifier.background(containerColor, pillShape)
                        },
                    )
                    .then(
                        if (isBlurActive) {
                            interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier)
                        } else {
                            Modifier
                        },
                    )
                    .then(dampedDrag.modifier)
                    .height(LiquidGlassBarHeight),
            )

            // ── Selection pill. A SIBLING of the tab Row, not a child. ───
            // Inside the Row its fixed width was measured as a plain (non-weighted)
            // child, which took 311px out of the row before the weighted slots were
            // resolved and squeezed all three tabs into the right-hand 621px. As a
            // sibling it is overlayed instead, so the slots keep the full width.
            if (isBlurActive && backdrop != null && tabWidthPx > 0f) {
                val tabWidthDp = with(density) { tabWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            val slotCenter = dampedDrag.value * tabWidthPx
                            translationX =
                                if (isLtr) slotCenter + panelOffset
                                else -slotCenter + panelOffset
                            val s = pressScale()
                            scaleX = s
                            scaleY = s
                        }
                        .drawBackdrop(
                            backdrop = combinedBackdrop ?: backdrop,
                            shape = { pillShape },
                            effects = {
                                vibrancy()
                                blur(4.dp.toPx(), 4.dp.toPx())
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            },
                            // No Miuix Highlight here: its BloomStroke paints a white
                            // stroke with BlendMode.Plus, which is a no-op on a light
                            // backdrop, so what survives is the shader's own black
                            // coverage — a black rim in light theme. A plain SrcOver
                            // stroke reads correctly in both themes.
                            highlight = { null },
                            onDrawSurface = { drawRect(selectedFill) },
                        )
                        .then(
                            pillRim(
                                shape = pillShape,
                                alpha = if (isDark) 0.12f else 0.18f,
                            ),
                        )
                        .height(LiquidGlassBarPillHeight)
                        .width(tabWidthDp),
                )
            }

            // ── Row B: the tab content. The ONLY copy. ────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { coords ->
                        totalWidthPx = coords.width.toFloat()
                        val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .height(LiquidGlassBarHeight)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The ONLY copy of the tab content. Each slot scales about its own
                // centre by the same factor as the selection pill (see
                // `LocalIosTabScale`), so the pill and its content grow as one object
                // and both stay inside their slot instead of overflowing the bar.
                CompositionLocalProvider(LocalIosTabScale provides pressScale) {
                    Row(
                        modifier = Modifier
                            .selectableGroup()
                            .fillMaxWidth()
                            .height(LiquidGlassBarPillHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        content = tabsContent,
                    )
                }
            }
        }
    }
}
