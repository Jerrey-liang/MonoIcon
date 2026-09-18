package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.liquid.LiquidGlassBarHeight
import com.jerrey.monoicon.ui.liquid.LiquidGlassBarVisualGap
import com.jerrey.monoicon.ui.liquid.LiquidGlassNavigationBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
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
 * Three Miuix tabs — 概览 / 图标样式 / 模块设置 — whose content scrolls behind a
 * floating **liquid-glass** bottom navigation bar (see
 * [LiquidGlassNavigationBar]: recorded backdrop + blur + vibrancy + lens
 * refraction, with drag-to-select tabs). The overview tab opens the runtime-log
 * and about pages as secondary screens, which hide the bar.
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

// ── Glass bottom bar footprint (single source for the content inset) ──────────
// The pill is a fixed design size, so its footprint is derived from the same
// numbers the bar draws with rather than measured at runtime.
private val GlassBarBreathingRoom = 4.dp

/**
 * Bottom spacing the scroll content must reserve so the last item is never
 * permanently hidden behind the floating bar: the drawn pill height plus the
 * visual gap the bar floats above the Scaffold's content edge, plus breathing
 * room.
 *
 * The Scaffold owns the system navigation inset — this constant must NOT
 * include it, and the bar must not add `navigationBarsPadding()` either.
 */
private val GlassBarContentInset =
    LiquidGlassBarHeight + LiquidGlassBarVisualGap + GlassBarBreathingRoom

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
                    LiquidGlassNavigationBar(
                        items = MainTab.entries.map { NavigationItem(icon = it.icon, label = it.label(strings)) },
                        selectedIndex = tab.ordinal,
                        onItemClick = { tab = MainTab.entries[it] },
                        backdrop = backdrop,
                        isBlurActive = glassReady,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}
