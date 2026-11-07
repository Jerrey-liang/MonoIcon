// Navigation adapted from KernelSU, GPL-3.0-or-later.
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2. See docs/THIRD_PARTY_NOTICES.md.
package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.icons.MonoIcons
import com.jerrey.monoicon.ui.liquid.BlurredBar
import com.jerrey.monoicon.ui.liquid.LiquidGlassNavigationBar
import com.jerrey.monoicon.ui.liquid.rememberBlurBackdrop
import com.jerrey.monoicon.ui.settings.UiPreferences
import com.jerrey.monoicon.ui.settings.rememberNavigationBadges
import com.jerrey.monoicon.ui.settings.settingsText
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.PagerNavigationSpringSpec
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.pagerGestureOverride
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.utils.springAnimateToPage

private enum class MainTab(val icon: ImageVector) {
    HOME(MonoIcons.Home),
    ICON_STYLE(MonoIcons.IconStyle),
    CONFIGURATION(MonoIcons.Configuration),
    MODULE(MonoIcons.Settings);

    fun label(strings: AppStrings): String = when (this) {
        HOME -> strings.overview
        ICON_STYLE -> strings.iconStyle
        CONFIGURATION -> strings.configurationManagement
        MODULE -> strings.moduleSettings
    }
}

private enum class AppRoute : NavKey { MAIN, LOGS, ABOUT, THEME }

// These routes carry no arguments; save enum names across process recreation.
private val RouteSaver = listSaver<NavBackStack, String>(
    save = { stack -> stack.map { (it as AppRoute).name } },
    restore = { names -> navBackStackOf(*names.map(AppRoute::valueOf).toTypedArray()) },
)

@Composable
fun SettingsScreen(variant: String, onVariantChange: (String) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var language by rememberSaveable { mutableStateOf(AppLanguage.fromId(ConfigManager.getLanguageFromUi())) }
    val strings = stringsFor(language, systemPrefersChinese(context))
    val settings by UiPreferences.state.collectAsState()
    val routes = rememberSaveable(saver = RouteSaver) { navBackStackOf(AppRoute.MAIN) }
    fun open(route: AppRoute) { if (route !in routes) routes.add(route) }
    fun back() { if (routes.size > 1) routes.removeAt(routes.lastIndex) }
    val dismiss = if (settings.swipeDismiss) {
        if (LocalLayoutDirection.current == LayoutDirection.Rtl) NavSwipeDirection.RightToLeft
        else NavSwipeDirection.LeftToRight
    } else NavSwipeDirection.None

    CompositionLocalProvider(LocalStrings provides strings) {
        Scaffold { _ ->
            NavDisplay(
                backStack = routes,
                effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                onBack = ::back,
            ) {
                entry<AppRoute>(swipeDismiss = dismiss) { route ->
                    when (route) {
                        AppRoute.MAIN -> MainPages(
                            variant, onVariantChange, language, {
                                language = it
                                activity?.recreate()
                            },
                            isActive = routes.last() == AppRoute.MAIN,
                            onOpenLogs = { open(AppRoute.LOGS) },
                            onOpenAbout = { open(AppRoute.ABOUT) },
                            onOpenTheme = { open(AppRoute.THEME) },
                        )
                        AppRoute.LOGS -> KernelPage(title = strings.logsTitle, onBack = ::back) {
                            LogScreen(isActive = routes.last() == AppRoute.LOGS)
                        }
                        AppRoute.ABOUT -> AboutScreen(onBack = ::back)
                        AppRoute.THEME -> KernelPage(
                            title = settingsText("settings_theme"),
                            onBack = ::back,
                        ) { ThemeSettingsScreen() }
                    }
                }
            }
        }
    }
}

/** KernelSU's MainPagerState semantics, including cancellation of interrupted taps. */
private class MainPagerState(val pager: PagerState, private val scope: CoroutineScope) {
    var selectedPage by mutableIntStateOf(pager.currentPage)
        private set
    private var navigating by mutableStateOf(false)
    private var job: Job? = null

    fun select(index: Int) {
        if (index == selectedPage) return
        job?.cancel()
        selectedPage = index
        navigating = true
        job = scope.launch {
            val currentJob = coroutineContext.job
            try {
                pager.springAnimateToPage(index)
            } finally {
                if (job == currentJob) {
                    navigating = false
                    selectedPage = pager.currentPage
                }
            }
        }
    }

    fun syncPage() { if (!navigating) selectedPage = pager.currentPage }
}

@Composable
private fun MainPages(
    variant: String,
    onVariantChange: (String) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    isActive: Boolean,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenTheme: () -> Unit,
) {
    val strings = LocalStrings.current
    val settings by UiPreferences.state.collectAsState()
    val badges = rememberNavigationBadges()
    val pager = rememberPagerState(pageCount = { MainTab.entries.size })
    val scope = rememberCoroutineScope()
    val mainState = remember(pager, scope) { MainPagerState(pager, scope) }
    val surface = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop { drawRect(surface); drawContent() }
    val barBackdrop = rememberBlurBackdrop(settings.enableBlur)
    val items = remember(strings) {
        MainTab.entries.map { NavigationItem(icon = it.icon, label = it.label(strings)) }
    }
    val pagerMode = PagerInterceptionMode.entries.getOrElse(settings.pagerGestureMode) { PagerInterceptionMode.CrossAxisInterceptor }
    val intercept = pagerMode == PagerInterceptionMode.CrossAxisInterceptor
    LaunchedEffect(pager.currentPage) { mainState.syncPage() }
    BackHandler(enabled = isActive && mainState.selectedPage != 0) { mainState.select(0) }

    Scaffold(
        modifier = if (isActive) Modifier else Modifier.semantics { hideFromAccessibility() },
        bottomBar = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                if (settings.floatingBottomBar) {
                    val inset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    LiquidGlassNavigationBar(
                        items = items,
                        selectedIndex = mainState.selectedPage,
                        onItemClick = mainState::select,
                        backdrop = backdrop,
                        isBlurEnabled = settings.glassBottomBar,
                        badges = badges,
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp, bottom = if (inset != 0.dp) 8.dp + inset else 28.dp),
                    )
                } else {
                    BlurredBar(barBackdrop) {
                        NavigationBar(
                            color = if (barBackdrop != null) Color.Transparent else surface,
                            content = {
                                items.forEachIndexed { index, item ->
                                    NavigationBarItem(
                                        modifier = Modifier.weight(1f),
                                        icon = item.icon,
                                        label = item.label,
                                        selected = mainState.selectedPage == index,
                                        onClick = { mainState.select(index) },
                                        badge = badges[index]?.let { count -> { Badge { Text(count.toString()) } } },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = if (barBackdrop != null) Modifier.layerBackdrop(barBackdrop) else Modifier) {
            HorizontalPager(
                modifier = Modifier.fillMaxSize()
                    .pagerGestureOverride(pagerState = pager, mode = pagerMode, enabled = isActive)
                    .then(if (settings.floatingBottomBar && settings.glassBottomBar) Modifier.layerBackdrop(backdrop) else Modifier),
                state = pager,
                beyondViewportPageCount = 3,
                overscrollEffect = null,
                userScrollEnabled = isActive && !intercept,
                pageNestedScrollConnection = if (intercept) PagerGestureNestedScrollConnection else
                    PagerDefaults.pageNestedScrollConnection(pager, androidx.compose.foundation.gestures.Orientation.Horizontal),
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = PagerNavigationSpringSpec),
            ) { index ->
                val tab = MainTab.entries[index]
                val active = isActive && index == pager.settledPage
                KernelPage(
                    title = if (tab == MainTab.HOME) "MonoIcon" else tab.label(strings),
                    actions = { if (tab != MainTab.MODULE) HomeTopBarActions() },
                    bottomPadding = padding.calculateBottomPadding(),
                    modifier = Modifier.testTag("main-page-$index").then(
                        if (active) Modifier else Modifier.semantics { hideFromAccessibility() },
                    ),
                ) {
                    when (tab) {
                        MainTab.HOME -> OverviewScreen(onOpenLogs = onOpenLogs, onOpenAbout = onOpenAbout, isActive = active)
                        MainTab.ICON_STYLE -> IconStyleScreen(variant, onVariantChange)
                        MainTab.CONFIGURATION -> ConfigurationScreen(isActive = active)
                        MainTab.MODULE -> ModuleSettingsScreen(
                            language = language,
                            onLanguageChange = onLanguageChange,
                            onOpenLogs = onOpenLogs,
                            onOpenAbout = onOpenAbout,
                            onOpenTheme = onOpenTheme,
                        )
                    }
                }
            }
        }
    }
}

/** Same collapsing title, scroll effects and texture blur as KernelSU's Miuix pages. */
@Composable
internal fun KernelPage(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val settings by UiPreferences.state.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(settings.enableBlur)
    val strings = LocalStrings.current
    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    title = title,
                    color = if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.surface,
                    scrollBehavior = scrollBehavior,
                    actions = { actions() },
                    navigationIcon = {
                        if (onBack != null) IconButton(onClick = onBack) {
                            Icon(MiuixIcons.Regular.Back, strings.back)
                        }
                    },
                )
            }
        },
        popupHost = {},
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { padding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = padding,
                overscrollEffect = null,
            ) {
                item {
                    Column { content() }
                    Spacer(Modifier.height(bottomPadding + if (onBack != null)
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp else 12.dp))
                }
            }
        }
    }
}
