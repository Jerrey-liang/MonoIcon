package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.noiseDither
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings host (Phase 13).
 *
 * Three Miuix tabs — 概览 / 模块设置 / 图标样式 — whose content scrolls behind a
 * floating **liquid-glass** bottom navigation bar. The glass is Haze 2's typed
 * Glass effect (`hazeGlass` + `GlassStyle`), i.e. real backdrop refraction
 * (optics/specular/fresnel/chromatic aberration) rather than a plain blur.
 * The overview tab opens the runtime-log and about pages as secondary screens.
 */
private enum class MainTab(val icon: ImageVector) {
    OVERVIEW(MiuixIcons.Regular.Home),
    MODULE(MiuixIcons.Regular.Settings),
    ICON_STYLE(MiuixIcons.Regular.Theme),
}

private enum class Overlay { NONE, LOGS, ABOUT }

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.fromId(ConfigManager.getLanguageFromUi())) }
    val strings = stringsFor(language, systemPrefersChinese(context))

    CompositionLocalProvider(LocalStrings provides strings) {
        var tab by remember { mutableStateOf(MainTab.OVERVIEW) }
        var overlay by remember { mutableStateOf(Overlay.NONE) }
        val backdrop = rememberLayerBackdrop()
        // 玻璃（含 source 侧整屏图层录制）延迟挂载：每进程首次 AGSL 编译 + 首帧图层录制开销很大
        var glassReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(150)
            glassReady = true
        }

        BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

        val title = when (overlay) {
            Overlay.LOGS -> strings.logsTitle
            Overlay.ABOUT -> strings.about
            Overlay.NONE -> when (tab) {
                MainTab.OVERVIEW -> "MonoIcon"
                MainTab.MODULE -> strings.moduleSettings
                MainTab.ICON_STYLE -> strings.iconStyle
            }
        }

        // 大标题收起：次级页（运行日志/关于）和内容较长的"图标样式"页都用同一套逻辑 ——
        // 顶栏只留返回键/空白，滚过大标题之后才切成居中标题。
        val collapseTitle = overlay != Overlay.NONE || tab == MainTab.ICON_STYLE
        val scrollState = rememberScrollState()
        var titleBottomPx by remember { mutableStateOf(0) }
        val barTitleVisible by remember {
            derivedStateOf { titleBottomPx > 0 && scrollState.value >= titleBottomPx }
        }
        LaunchedEffect(overlay, tab) {
            scrollState.scrollTo(0)
            titleBottomPx = 0
        }

        Scaffold(
            topBar = {
                // Main tabs carry their own large left-aligned title (Liquid
                // Glass large-title pattern); secondary pages need the bar for
                // their back affordance.
                if (collapseTitle) {
                    SmallTopAppBar(
                        title = if (barTitleVisible) title else "",
                        navigationIcon = {
                            if (overlay != Overlay.NONE) {
                                IconButton(onClick = { overlay = Overlay.NONE }) {
                                    Icon(
                                        imageVector = MiuixIcons.Regular.Back,
                                        contentDescription = strings.back,
                                    )
                                }
                            }
                        },
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .padding(padding),
            ) {
                // ── Page content (blur source for the glass bar) ───────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.background)
                        .then(if (glassReady) Modifier.layerBackdrop(backdrop) else Modifier)
                        .verticalScroll(scrollState)
                        .padding(top = 4.dp, bottom = 120.dp),
                ) {
                    when (overlay) {
                        Overlay.LOGS -> {
                            CollapsibleLargeTitle(text = title) { titleBottomPx = it }
                            LogScreen()
                        }

                        Overlay.ABOUT -> {
                            CollapsibleLargeTitle(text = title) { titleBottomPx = it }
                            AboutScreen()
                        }

                        Overlay.NONE -> when (tab) {
                            MainTab.OVERVIEW -> OverviewScreen(
                                onOpenLogs = { overlay = Overlay.LOGS },
                                onOpenAbout = { overlay = Overlay.ABOUT },
                            )

                            MainTab.MODULE -> ModuleSettingsScreen(
                                language = language,
                                onLanguageChange = { language = it },
                            )

                            MainTab.ICON_STYLE -> IconStyleScreen(
                                onTitleBottomPositioned = { titleBottomPx = it },
                            )
                        }
                    }
                }

                // ── Liquid-glass bottom navigation ─────────────────────
                if (overlay == Overlay.NONE) {
                    GlassNavigationBar(
                        tab = tab,
                        onTabChange = { tab = it },
                        backdrop = backdrop,
                        glassReady = glassReady,
                        labels = listOf(strings.overview, strings.moduleSettings, strings.iconStyle),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            // LSPosed 量出来的胶囊几何：左右边距 40dp、离底 ~14dp、高 61dp
                            .padding(horizontal = 40.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Liquid-glass bottom navigation — miuix-blur engine, wired exactly like LSPosed
 * Manager's nav bar (`bt3.c` → `ng2.f0`):
 *  - the page content is recorded through `Modifier.layerBackdrop`;
 *  - `Modifier.drawBackdrop` blurs it (25.dp, noise 0.0045) and paints **one**
 *    `SrcOver` layer of the theme surface at alpha 0.8 inside the capsule;
 *  - **no rim highlight**: LSPosed's nav bar passes `highlight = null`.
 *
 * Two traps found while matching this against the decompiled manager:
 *  1. miuix's bundled `BloomStroke.GlassStroke*` presets carry `blendMode = 0`
 *     (== `BlendMode.Clear`) and `HighlightDrawingKt` paints the shader over the
 *     whole node rect; the BloomStroke SDF skips only the area further than
 *     `R = max(cornerRadius, innerBlurRadius)` from the edge — for a *capsule*
 *     that band is half the height, i.e. the whole shape. The result was the flat
 *     grey plate we saw (the slab erased, then re-tinted over black).
 *  2. In Material 3 the glass reads as a faint capsule only because `surface`
 *     sits ~4/255 below `background`. This device's Monet palette has them
 *     *identical*, so a literal copy is invisible on an empty page — hence the
 *     20% nudge towards `surfaceContainerHigh` (≈ LSPosed's 250,242,251).
 */
@Composable
private fun GlassNavigationBar(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    backdrop: LayerBackdrop,
    glassReady: Boolean,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val glassTint = lerp(colors.surface, colors.surfaceContainerHigh, 0.2f)
    val blurColors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = glassTint.copy(alpha = 0.8f),
                mode = BlurBlendMode.SrcOver,
            ),
        ),
        brightness = 0f,
        contrast = 1f,
        saturation = 1f,
    )
    val barModifier = if (glassReady) {
        modifier
            .fillMaxWidth()
            // 悬浮感的两条线索（LSPosed 的胶囊都有、miuix 的 drawBackdrop 本身不画）：
            // 3dp 阴影（同它 Surface 的 Modifier.shadow(elevation = 3.dp, ambient/spot = 黑)，
            // 但参考图峰值只暗 ~12/255，所以把颜色 alpha 压到 0.25）+ 一圈 1px 亮边。
            .shadow(
                elevation = 3.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    blur(25.dp.toPx(), 25.dp.toPx())
                    noiseDither(0.0045f)
                    blendColors(blurColors)
                },
            )
            .drawWithContent {
                drawContent()
                // 1px 亮边：参考图边缘实测 (255,250,255)/(255,255,255)，比填充 (250,242,251) 亮 ~4-5。
                // 这里用 SrcOver 的白 0.8（实测 Plus 叠加在该离屏图层里不生效，见提交说明）。
                val outline = shape.createOutline(size, layoutDirection, this)
                drawPath(
                    path = Path().apply { addOutline(outline) },
                    color = Color.White.copy(alpha = 0.8f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(vertical = 4.dp)
    } else {
        modifier.fillMaxWidth().padding(vertical = 4.dp)
    }

    Row(
        modifier = barModifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEachIndexed { index, entry ->
            val selected = entry == tab
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.92f else 1f,
                label = "navItemScale",
            )
            val color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariantSummary

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(percent = 50))
                    // LSPosed-style selection pill behind the whole item.
                    .background(
                        if (selected) colors.primaryContainer else Color.Transparent,
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onTabChange(entry) }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = labels.getOrNull(index),
                    tint = color,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = labels.getOrNull(index).orEmpty(),
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp),
                    color = color,
                )
            }
        }
    }
}