package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blendColors
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.noiseDither
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
 * Settings host (Phase 13). Three Miuix tabs — 概览 / 图标样式 / 模块设置 — whose content
 * scrolls behind a floating **liquid-glass** bottom navigation bar built on
 * miuix-blur, the engine LSPosed Manager uses (blurred backdrop + one translucent
 * surface layer; no refraction). The overview tab opens the runtime-log and about
 * pages as secondary screens.
 */
/** Bottom navigation tabs, in bar order (概览 / 图标样式 / 模块设置). */
private enum class MainTab(val icon: ImageVector) {
    OVERVIEW(MiuixIcons.Regular.Home),
    ICON_STYLE(MiuixIcons.Regular.Theme),
    MODULE(MiuixIcons.Regular.Settings),
}

/** Bar label for a tab; derived here so the label order can never drift from the enum. */
private fun MainTab.label(strings: AppStrings): String = when (this) {
    MainTab.OVERVIEW -> strings.overview
    MainTab.ICON_STYLE -> strings.iconStyle
    MainTab.MODULE -> strings.moduleSettings
}

private enum class Overlay { NONE, LOGS, ABOUT }

// ── Glass bottom bar geometry (single source for position AND content inset) ──
// The pill is a fixed design size, so the bar's footprint is derived from the
// same numbers the bar draws with rather than measured at runtime.
private val GlassBarContentHeight = 56.dp
private val GlassBarInnerPadding = 4.dp
private val GlassBarVisualGap = 14.dp

/** Drawn height of the glass pill (content + its own vertical padding). */
private val GlassBarHeight = GlassBarContentHeight + GlassBarInnerPadding * 2

/**
 * Bottom spacing the scroll content must reserve so the last item is never
 * permanently hidden behind the floating bar: the bar itself (which already
 * floats [GlassBarVisualGap] above the Scaffold content edge) plus breathing
 * room.
 *
 * The Scaffold owns the system navigation inset — this constant must NOT
 * include it, and the bar must not add `navigationBarsPadding()` either.
 */
private val GlassBarContentInset =
    GlassBarHeight + GlassBarVisualGap + GlassBarInnerPadding

@Composable
fun SettingsScreen(
    variant: String,
    onVariantChange: (String) -> Unit,
) {
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
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MiuixTheme.colorScheme.background,
                                    MiuixTheme.colorScheme.surfaceContainer,
                                ),
                            ),
                        )
                        .then(if (glassReady) Modifier.layerBackdrop(backdrop) else Modifier)
                        .verticalScroll(scrollState)
                        .padding(top = 4.dp, bottom = GlassBarContentInset),
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
                                variant = variant,
                                onVariantChange = onVariantChange,
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
                        labels = MainTab.entries.map { it.label(strings) },
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
 * Liquid-glass bottom navigation — miuix-blur engine, wired like LSPosed
 * Manager's nav bar (`bt3.c`):
 *  - the page content is recorded through `Modifier.layerBackdrop`;
 *  - `Modifier.drawBackdrop` blurs it (25.dp, noise 0.0045) and paints **one**
 *    translucent `SrcOver` layer of a dynamic-colour container role inside the
 *    capsule — the role, not a hand-mixed tint, is what keeps the glass on the
 *    same colour system as the page;
 *  - the specular rim comes from Miuix's own `Highlight.GlassStroke*` preset.
 *
 * Miuix 0.9.3's `BloomStroke` presets composite with `BlendMode.Plus`. An
 * earlier build of this bar shipped a hand-drawn 1px white outline because the
 * presets then in use carried `blendMode = 0` (== `Clear`), which erased the
 * capsule into a flat grey plate; that workaround is no longer needed and the
 * preset is used directly.
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
    // LSPosed's bar paints ONE translucent layer of the theme's container role over
    // the blur (`bt3.c`: `pt.b(0.8f, colorScheme.containerRole)`) — the tint is a
    // dynamic-colour role, never a hand-tuned mix of two roles.
    val blurColors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(
                color = colors.surfaceContainer.copy(alpha = 0.4f),
                mode = BlurBlendMode.SrcOver,
            ),
        ),
        brightness = 0f,
        contrast = 1f,
        saturation = 1f,
    )
    // Miuix's own specular edge preset (BloomStroke, blendMode = Plus). The dark
    // preset has the thinner inner blur the dark surface needs.
    val highlight = if (isSystemInDarkTheme()) {
        Highlight.GlassStrokeMiddleDark
    } else {
        Highlight.GlassStrokeMiddleLight
    }
    val barModifier = if (glassReady) {
        modifier
            .fillMaxWidth()
            // Floating cue: a 3dp drop shadow. Black is the physically correct
            // shadow colour (it is not a semantic foreground role).
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
                highlight = { highlight },
            )
            .padding(vertical = GlassBarInnerPadding)
    } else {
        modifier.fillMaxWidth().padding(vertical = GlassBarInnerPadding)
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