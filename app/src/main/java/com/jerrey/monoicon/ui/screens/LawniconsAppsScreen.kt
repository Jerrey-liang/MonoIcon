// App cards and controls follow KernelSU SuperUserMiuix and SettingsMiuix (GPL-3.0-or-later).
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.icons.MonoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun LawniconsAppsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenApp: (LawniconsApp) -> Unit = {},
    loadApps: suspend (Context) -> List<LawniconsApp> = LawniconsAppsRepository::loadLauncherApps,
    loadOriginalIcon: suspend (Context, LawniconsApp) -> Bitmap? = LawniconsAppsRepository::loadOriginalIcon,
) {
    val strings = LocalStrings.current
    val words = rememberIconPreferencesStrings()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<LawniconsApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var iconRevision by remember { mutableIntStateOf(0) }
    var overrides by remember { mutableStateOf(ConfigManager.getAllAppIconOverridesFromUi()) }
    var searchStatus by rememberSaveable(stateSaver = KernelSearchStatus.Saver) {
        mutableStateOf(KernelSearchStatus())
    }
    var searchTopPadding by remember { mutableStateOf(12.dp) }
    val query = searchStatus.searchText
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var styleFilter by rememberSaveable { mutableIntStateOf(0) }
    var roundnessFilter by rememberSaveable { mutableIntStateOf(0) }
    var sizeFilter by rememberSaveable { mutableIntStateOf(0) }
    var borderFilter by rememberSaveable { mutableIntStateOf(0) }
    var artworkRoundnessFilter by rememberSaveable { mutableIntStateOf(0) }
    var artworkFollowRoundnessFilter by rememberSaveable { mutableIntStateOf(0) }
    var nativeFilter by rememberSaveable { mutableIntStateOf(0) }
    var lawniconsFilter by rememberSaveable { mutableIntStateOf(0) }
    val filters = AppIconFilters(
        OverrideFilter.entries[styleFilter], OverrideFilter.entries[roundnessFilter],
        OverrideFilter.entries[sizeFilter], OverrideFilter.entries[borderFilter],
        ResourceFilter.entries[nativeFilter], ResourceFilter.entries[lawniconsFilter],
        artworkRoundness = OverrideFilter.entries[artworkRoundnessFilter],
        artworkFollowRoundness = OverrideFilter.entries[artworkFollowRoundnessFilter],
    )
    val filteredApps = remember(apps, overrides, query, filters) {
        apps.filter { filters.matches(it, overrides[it.packageName] ?: AppIconOverrides(), query) }
    }
    val filterRotation by animateFloatAsState(if (showFilters) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "filter-chevron")

    DisposableEffect(Unit) {
        val listener: (String?) -> Unit = { key ->
            if (key != "ui_language") scope.launch {
                overrides = ConfigManager.getAllAppIconOverridesFromUi()
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    DisposableEffect(context, activity) {
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        var firstResume = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else {
                    overrides = ConfigManager.getAllAppIconOverridesFromUi()
                    reload++
                }
            }
        }
        lifecycle?.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { reload++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycle?.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(context, reload) {
        if (reload > 0) delay(120)
        loading = true
        failed = false
        try {
            LawniconsAppsRepository.invalidatePreviews()
            apps = loadApps(context).distinctBy { it.packageName }
            iconRevision++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun LazyListScope.appListContent() {
        item(key = "search-filters") {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter, vertical = 12.dp)) {
                BasicComponent(
                    title = words.filters,
                    summary = if (filters.activeCount == 0) words.all else filters.activeCount.toString(),
                    startAction = { IconStylePreferenceIcon(MiuixIcons.Regular.Filter) },
                    endActions = { Icon(MiuixIcons.Regular.ExpandMore, null, Modifier.rotate(filterRotation)) },
                    modifier = Modifier.testTag("app-icon-filters"),
                    onClick = { showFilters = !showFilters },
                )
                AnimatedIconSettingsContent(visible = showFilters) {
                    Column {
                        val overrideItems = listOf(words.all, words.defaultFilter, words.custom)
                        val resourceItems = listOf(words.all, words.available, words.unavailable)
                        FilterPreference(words.iconStyle, "style", overrideItems, styleFilter) { styleFilter = it }
                        FilterPreference(strings.iconRoundness, "roundness", overrideItems, roundnessFilter) { roundnessFilter = it }
                        FilterPreference(words.artworkRoundness, "artwork-roundness", overrideItems, artworkRoundnessFilter) { artworkRoundnessFilter = it }
                        FilterPreference(words.artworkFollowIconRoundness, "artwork-follow-roundness", overrideItems, artworkFollowRoundnessFilter) { artworkFollowRoundnessFilter = it }
                        FilterPreference(strings.iconSize, "size", overrideItems, sizeFilter) { sizeFilter = it }
                        FilterPreference(strings.iconBorderSize, "border", overrideItems, borderFilter) { borderFilter = it }
                        FilterPreference(words.hasNative, "native", resourceItems, nativeFilter) { nativeFilter = it }
                        FilterPreference(words.hasLawnicons, "lawnicons", resourceItems, lawniconsFilter) { lawniconsFilter = it }
                        TextButton(
                            text = words.clearFilters,
                            onClick = {
                                styleFilter = 0; roundnessFilter = 0; sizeFilter = 0
                                borderFilter = 0; nativeFilter = 0; lawniconsFilter = 0
                                artworkRoundnessFilter = 0; artworkFollowRoundnessFilter = 0
                            },
                            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("app-icon-clear-filters"),
                        )
                    }
                }
            }
            Text(words.perAppHint, style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageSpacing.titleStart).padding(bottom = 8.dp))
            if (!loading && !failed) Text(words.resultCount(filteredApps.size),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = PageSpacing.titleStart).padding(bottom = 12.dp)
                    .testTag("app-icon-result-count"))
        }
        when {
            loading -> item(key = "loading") { AppListMessage(words.loading) }
            failed -> item(key = "error") {
                Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                    BasicComponent(title = words.error)
                    TextButton(text = strings.retry, onClick = { reload++ },
                        modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            apps.isEmpty() -> item(key = "empty") { AppListMessage(words.noApps) }
            filteredApps.isEmpty() -> item(key = "no-match") { AppListMessage(words.noMatches) }
            else -> items(filteredApps, key = { it.packageName }, contentType = { "app-icon" }) { app ->
                AppIconCard(
                    app = app,
                    iconRevision = iconRevision,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(180), fadeOutSpec = tween(120),
                        placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
                    onClick = { onOpenApp(app) },
                    loadOriginalIcon = loadOriginalIcon,
                )
            }
        }
    }

    KernelLazyPage(
        title = words.perAppTitle,
        modifier = modifier.testTag("app-icon-settings-page"),
        onBack = onBack,
        actions = { HomeTopBarActions(Modifier.testTag("app-icon-list-restart")) },
        topBarContent = { collapsedFraction ->
            val dynamicTopPadding = 12.dp * (1f - collapsedFraction)
            LaunchedEffect(dynamicTopPadding) { searchTopPadding = dynamicTopPadding }
            Box(Modifier.fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val offset = with(density) { coordinates.positionInWindow().y.toDp() }
                    if (searchStatus.offsetY != offset) searchStatus = searchStatus.copy(offsetY = offset)
                }
                .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                .clickable(enabled = searchStatus.isCollapsed(), interactionSource = null, indication = null) {
                    searchStatus = searchStatus.copy(current = KernelSearchStatus.Status.EXPANDING)
                }.testTag("app-icon-search-open")) {
                KernelSearchBarFake(words.search, dynamicTopPadding)
            }
        },
        popupHost = {
            KernelSearchPager(searchStatus, { searchStatus = it }, words.clearSearch, searchTopPadding) {
                val bottomInset = maxOf(
                    WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
                LazyColumn(Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
                    contentPadding = PaddingValues(top = 6.dp, bottom = bottomInset + 12.dp),
                    overscrollEffect = null) {
                    appListContent()
                }
            }
        },
    ) {
        if (searchStatus.shouldCollapse()) appListContent()
    }
}

@Composable
private fun FilterPreference(title: String, tag: String, items: List<String>, selected: Int, onChange: (Int) -> Unit) {
    OverlayDropdownPreference(title = title, items = items, selectedIndex = selected,
        modifier = Modifier.testTag("app-icon-filter-$tag"), onSelectedIndexChange = onChange)
}

@Composable
private fun AppListMessage(message: String) {
    Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) { BasicComponent(title = message) }
}

@Composable
private fun AppIconCard(
    app: LawniconsApp,
    iconRevision: Int,
    modifier: Modifier,
    onClick: () -> Unit,
    loadOriginalIcon: suspend (Context, LawniconsApp) -> Bitmap?,
) {
    val context = LocalContext.current
    val words = rememberIconPreferencesStrings()
    val original by produceState<ImageBitmap?>(null, app.identity, iconRevision) {
        try {
            value = loadOriginalIcon(context, app)?.asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = null
        }
    }
    Card(modifier = modifier.padding(horizontal = PageSpacing.gutter).padding(bottom = 12.dp)
        .fillMaxWidth().testTag("app-icon-card-${app.packageName}")) {
        ArrowPreference(
            title = app.label,
            summary = app.packageName,
            modifier = Modifier.testTag("app-icon-edit-${app.packageName}"),
            startAction = {
                Crossfade(targetState = original, animationSpec = tween(180), label = "original-app-icon",
                    modifier = Modifier.padding(end = 12.dp).size(40.dp).testTag("app-original-icon-${app.packageName}")) { icon ->
                    val description = words.originalIconDescription(app.label)
                    if (icon != null) Image(icon, description, Modifier.size(40.dp))
                    else Icon(MonoIcons.IconStyle, description, modifier = Modifier.size(40.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            },
            onClick = onClick,
        )
    }
}

/** Shared motion for filters, per-app custom sliders and reset controls. */
@Composable
internal fun AnimatedIconSettingsContent(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) + expandVertically(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(100)) + shrinkVertically(
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)),
    ) { content() }
}
