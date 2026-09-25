package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.liquid.LiquidGlassNavigationBar
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

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

@Composable
fun SettingsScreen(
    variant: String,
    onVariantChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    var language by remember { mutableStateOf(AppLanguage.fromId(ConfigManager.getLanguageFromUi())) }
    val strings = stringsFor(language, systemPrefersChinese(context))

    CompositionLocalProvider(LocalStrings provides strings) {
        var tab by remember { mutableStateOf(MainTab.OVERVIEW) }
        var overlay by remember { mutableStateOf(Overlay.NONE) }
        // KernelSU's page source; the bottomBar consumer is outside its subtree.
        val backgroundColor = MiuixTheme.colorScheme.surface
        val backdrop = rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
        val barBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }

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
            containerColor = backgroundColor,
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
            // Never move this consumer inside layerBackdrop(backdrop): recording
            // its own RenderNode would create a recursive drawing graph.
            bottomBar = {
                if (overlay == Overlay.NONE) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        LiquidGlassNavigationBar(
                            items = MainTab.entries.map { NavigationItem(icon = it.icon, label = it.label(strings)) },
                            selectedIndex = tab.ordinal,
                            onItemClick = { tab = MainTab.entries[it] },
                            backdrop = backdrop,
                            modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = barBottomPadding),
                        )
                    }
                }
            },
        ) { padding ->
            // The source extends behind the bar. Reserve its measured height only
            // at the end of the scrolling content, never outside the recorder.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = padding.calculateStartPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        end = padding.calculateEndPadding(layoutDirection),
                    )
                    .layerBackdrop(backdrop),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(top = 4.dp, bottom = padding.calculateBottomPadding()),
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
            }
        }
    }
}
