// Navigation adapted from KernelSU, GPL-3.0-or-later.
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2. See docs/THIRD_PARTY_NOTICES.md.
package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import java.util.Base64
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

private enum class AppRoute : NavKey { MAIN, LOGS, ABOUT, THEME, LAWNICONS_APPS, ICON_ROUNDNESS, ICON_SIZE, ICON_BORDER, ARTWORK_ROUNDNESS }
private data class AppIconRoute(val app: LawniconsApp) : NavKey

// Encode app metadata as separate fields so names containing separators and
// non-ASCII text survive process recreation without re-querying the entire list.
private val RouteSaver = listSaver<NavBackStack, String>(
    save = { stack -> stack.map { route ->
        when (route) {
            is AppIconRoute -> "APP_ICON|" + route.app.let { app ->
                listOf(app.packageName, app.label, app.identity,
                    app.hasNativeMonochrome.toString(), app.hasLawnicons.toString()).joinToString("|") {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
                }
            }
            else -> (route as AppRoute).name
        }
    } },
    restore = { names -> navBackStackOf(*names.map { saved ->
        if (saved.startsWith("APP_ICON|")) {
            val fields = saved.removePrefix("APP_ICON|").split('|').map {
                String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
            }
            AppIconRoute(LawniconsApp(fields[0], fields[1], fields[2], fields[3].toBoolean(), fields[4].toBoolean()))
        } else AppRoute.valueOf(saved)
    }.toTypedArray()) },
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
                            onOpenLawniconsApps = { open(AppRoute.LAWNICONS_APPS) },
                            onOpenParameter = { parameter ->
                                open(when (parameter) {
                                    IconStyleParameter.ROUNDNESS -> AppRoute.ICON_ROUNDNESS
                                    IconStyleParameter.SIZE -> AppRoute.ICON_SIZE
                                    IconStyleParameter.BORDER -> AppRoute.ICON_BORDER
                                    IconStyleParameter.ARTWORK_ROUNDNESS -> AppRoute.ARTWORK_ROUNDNESS
                                })
                            },
                        )
                        AppRoute.LOGS -> KernelPage(title = strings.logsTitle, onBack = ::back) {
                            LogScreen(isActive = routes.last() == AppRoute.LOGS)
                        }
                        AppRoute.ABOUT -> AboutScreen(onBack = ::back)
                        AppRoute.THEME -> KernelPage(
                            title = settingsText("settings_theme"),
                            onBack = ::back,
                        ) { ThemeSettingsScreen() }
                        AppRoute.LAWNICONS_APPS -> LawniconsAppsScreen(
                            onBack = ::back,
                            onOpenApp = { app -> routes.add(AppIconRoute(app)) },
                        )
                        AppRoute.ICON_ROUNDNESS -> IconStyleParameterScreen(IconStyleParameter.ROUNDNESS, ::back)
                        AppRoute.ICON_SIZE -> IconStyleParameterScreen(IconStyleParameter.SIZE, ::back)
                        AppRoute.ICON_BORDER -> IconStyleParameterScreen(IconStyleParameter.BORDER, ::back)
                        AppRoute.ARTWORK_ROUNDNESS -> IconStyleParameterScreen(IconStyleParameter.ARTWORK_ROUNDNESS, ::back)
                    }
                }
                entry<AppIconRoute>(swipeDismiss = dismiss) { route ->
                    AppIconSettingsScreen(app = route.app, onBack = ::back)
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
    onOpenLawniconsApps: () -> Unit,
    onOpenParameter: (IconStyleParameter) -> Unit,
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
                    pinnedContent = if (tab == MainTab.ICON_STYLE) {
                        { GlobalIconStylePreview(variant) }
                    } else null,
                    modifier = Modifier.testTag("main-page-$index").then(
                        if (active) Modifier else Modifier.semantics { hideFromAccessibility() },
                    ),
                ) {
                    when (tab) {
                        MainTab.HOME -> OverviewScreen(onOpenLogs = onOpenLogs, onOpenAbout = onOpenAbout, isActive = active)
                        MainTab.ICON_STYLE -> IconStyleScreen(
                            variant, onVariantChange, onOpenLawniconsApps = onOpenLawniconsApps,
                            onOpenParameter = onOpenParameter,
                        )
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
    pinnedContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    KernelLazyPage(title, modifier, bottomPadding, onBack, actions, pinnedContent) {
        item { Column { content() } }
    }
}

/** App lists use the same page chrome while composing only visible rows. */
@Composable
internal fun KernelLazyPage(
    title: String,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    pinnedContent: (@Composable () -> Unit)? = null,
    topBarContent: (@Composable (Float) -> Unit)? = null,
    popupHost: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val settings by UiPreferences.state.collectAsState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop(settings.enableBlur)
    val strings = LocalStrings.current
    val layoutDirection = LocalLayoutDirection.current
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
                    bottomContent = {
                        topBarContent?.invoke(scrollBehavior.state.collapsedFraction)
                    },
                )
            }
        },
        popupHost = popupHost,
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
            .then(if (pinnedContent != null) Modifier.padding(
                start = padding.calculateStartPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(layoutDirection),
            ) else Modifier)) {
            val previewLimit = minOf(maxHeight * 0.6f, (maxHeight - 120.dp).coerceAtLeast(0.dp))
            Column(Modifier.fillMaxSize()) {
                if (pinnedContent != null) {
                    // Preserve the preview itself. Short landscape/split windows
                    // scroll its contents independently and retain space for controls.
                    Box(Modifier.fillMaxWidth().heightIn(max = previewLimit)
                        .testTag("pinned-preview-host").verticalScroll(rememberScrollState())) {
                        pinnedContent()
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("kernel-page-scroll").scrollEndHaptic().overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = if (pinnedContent == null) padding else PaddingValues(bottom = padding.calculateBottomPadding()),
                    overscrollEffect = null,
                ) {
                    content()
                    item { Spacer(Modifier.height(bottomPadding + if (onBack != null)
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp else 12.dp)) }
                }
            }
        }
    }
}
