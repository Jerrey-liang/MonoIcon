package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.liquid.FrostedTopAppBar
import com.jerrey.monoicon.ui.liquid.LiquidGlassNavigationBar
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
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

private const val PAGE_TRANSITION_DURATION_MS = 300

/** Direction belongs to the latest request, including interrupted transitions. */
private data class PageMotion(
    val page: Pair<MainTab, Overlay>,
    val forward: Boolean,
)

private fun movesForward(from: Pair<MainTab, Overlay>, to: Pair<MainTab, Overlay>): Boolean =
    if (from.second != to.second) to.second != Overlay.NONE else to.first.ordinal > from.first.ordinal

@Composable
fun SettingsScreen(
    variant: String,
    onVariantChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    var language by remember { mutableStateOf(AppLanguage.fromId(ConfigManager.getLanguageFromUi())) }
    val strings = stringsFor(language, systemPrefersChinese(context))
    val navigationItems = remember(strings) {
        MainTab.entries.map { NavigationItem(icon = it.icon, label = it.label(strings)) }
    }

    CompositionLocalProvider(LocalStrings provides strings) {
        var tab by remember { mutableStateOf(MainTab.OVERVIEW) }
        var overlay by remember { mutableStateOf(Overlay.NONE) }
        // Both bars sample this page source from outside its recording subtree.
        val backgroundColor = MiuixTheme.colorScheme.surface
        val backdrop = rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
        val barBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }

        val page = tab to overlay
        val motionState = remember { SeekableTransitionState(PageMotion(page, forward = true)) }
        val pageTransition = rememberTransition(motionState, label = "Page transition")
        var activeBackGesture by remember { mutableStateOf<Any?>(null) }

        // One animation owner handles clicks, committed back and cancelled back.
        // Resuming here also avoids animating from a cancelled gesture coroutine.
        LaunchedEffect(page, activeBackGesture) {
            if (activeBackGesture == null) {
                val previousTarget = motionState.targetState
                val target = if (previousTarget.page == page) previousTarget else {
                    PageMotion(page, forward = movesForward(previousTarget.page, page))
                }
                motionState.animateTo(target)
            }
        }

        PredictiveBackHandler(enabled = overlay != Overlay.NONE) { events ->
            val origin = tab to overlay
            // A gesture begun during a push can still commit/cancel normally;
            // leave that in-flight animation intact rather than snapping it.
            val gesture = if (!pageTransition.isRunning && motionState.currentState.page == origin) Any() else null
            if (gesture != null) activeBackGesture = gesture
            try {
                events.collect { event ->
                    if (gesture != null && activeBackGesture === gesture && (tab to overlay) == origin) {
                        motionState.seekTo(event.progress, PageMotion(tab to Overlay.NONE, forward = false))
                    }
                }
                if ((tab to overlay) == origin) overlay = Overlay.NONE
            } catch (_: CancellationException) {
                // Keep the destination; the effect above restores the page.
            } finally {
                if (gesture != null && activeBackGesture === gesture) activeBackGesture = null
            }
        }

        val title = when (overlay) {
            Overlay.LOGS -> strings.logsTitle
            Overlay.ABOUT -> strings.about
            Overlay.NONE -> when (tab) {
                MainTab.OVERVIEW -> "MonoIcon"
                MainTab.MODULE -> strings.moduleSettings
                MainTab.ICON_STYLE -> strings.iconStyle
            }
        }

        var barTitleVisible by remember(page) { mutableStateOf(false) }
        var lastPrimaryBottomInset by remember { mutableStateOf(0.dp) }

        Scaffold(
            containerColor = backgroundColor,
            // The bars own their system insets. Content starts below the top bar
            // but scrolls behind it so the backdrop contains the actual page.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                FrostedTopAppBar(
                    title = if (barTitleVisible) title else "",
                    backdrop = backdrop,
                    modifier = Modifier.zIndex(1f),
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
            },
            // Never move this consumer inside layerBackdrop(backdrop): recording
            // its own RenderNode would create a recursive drawing graph.
            bottomBar = {
                if (overlay == Overlay.NONE) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        LiquidGlassNavigationBar(
                            items = navigationItems,
                            selectedIndex = tab.ordinal,
                            onItemClick = { tab = MainTab.entries[it] },
                            backdrop = backdrop,
                            modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = barBottomPadding),
                        )
                    }
                }
            },
        ) { padding ->
            val primaryBottomInset = if (overlay == Overlay.NONE) {
                padding.calculateBottomPadding()
            } else {
                lastPrimaryBottomInset
            }
            SideEffect {
                if (overlay == Overlay.NONE) lastPrimaryBottomInset = primaryBottomInset
            }

            // Record the complete viewport, including the area behind both bars.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                    )
                    .clipToBounds()
                    .layerBackdrop(backdrop),
            ) {
                pageTransition.AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it.page },
                    transitionSpec = {
                        val navigationSign = if (targetState.forward) 1 else -1
                        val direction = navigationSign * if (layoutDirection == LayoutDirection.Ltr) 1 else -1
                        val enter = slideInHorizontally(
                            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
                            initialOffsetX = { width -> direction * width },
                        ) + fadeIn(tween(PAGE_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing))
                        val exit = slideOutHorizontally(
                            animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing),
                            targetOffsetX = { width -> -direction * width },
                        ) + fadeOut(tween(PAGE_TRANSITION_DURATION_MS, easing = FastOutSlowInEasing))
                        (enter togetherWith exit).using(null)
                    },
                ) { displayed ->
                    val isPageActive = displayed.page == page && activeBackGesture == null
                    // Each concurrently visible page retains its own scroll and
                    // measured title, so entering a page never moves the old one.
                    val scrollState = rememberScrollState()
                    var titleBottomPx by remember { mutableStateOf(0) }
                    val pageTitleVisible by remember {
                        derivedStateOf { titleBottomPx > 0 && scrollState.value >= titleBottomPx }
                    }
                    val showPageTitle = pageTitleVisible
                    SideEffect {
                        if (displayed.page == page) barTitleVisible = showPageTitle
                    }
                    val bottomInset = if (displayed.page.second == Overlay.NONE) primaryBottomInset else 0.dp
                    val pageInput = if (isPageActive) Modifier else {
                        Modifier
                            .semantics { hideFromAccessibility() }
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(pageInput)
                            .verticalScroll(scrollState)
                            .padding(
                                top = padding.calculateTopPadding(),
                                bottom = bottomInset + PageSpacing.gutter,
                            ),
                    ) {
                        when (displayed.page.second) {
                            Overlay.LOGS -> PageContent(
                                title = strings.logsTitle,
                                onTitleBottomPositioned = { titleBottomPx = it },
                            ) { LogScreen(isActive = isPageActive) }

                            Overlay.ABOUT -> PageContent(
                                title = strings.about,
                                onTitleBottomPositioned = { titleBottomPx = it },
                            ) { AboutScreen() }

                            Overlay.NONE -> when (displayed.page.first) {
                                MainTab.OVERVIEW -> OverviewScreen(
                                    onOpenLogs = { overlay = Overlay.LOGS },
                                    onOpenAbout = { overlay = Overlay.ABOUT },
                                    onTitleBottomPositioned = { titleBottomPx = it },
                                    isActive = isPageActive,
                                )

                                MainTab.MODULE -> ModuleSettingsScreen(
                                    language = language,
                                    onLanguageChange = { language = it },
                                    onTitleBottomPositioned = { titleBottomPx = it },
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
}
