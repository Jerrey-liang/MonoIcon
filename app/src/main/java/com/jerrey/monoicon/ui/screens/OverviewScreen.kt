package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.ScopeApp
import com.jerrey.monoicon.ui.XposedState
import com.jerrey.monoicon.ui.requestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Compact card geometry; text can increase the height at larger font scales. */
private object HeroCard {
    val minHeight = 120.dp
    val shape = RoundedCornerShape(20.dp)
    val textEnd = 96.dp
    val textVerticalPadding = 16.dp
    val versionGap = 4.dp
    val apiGap = 10.dp

    val checkRing = 112.dp
    val checkRingWidth = 10.dp
    val checkRingPadding = 16.dp
    val checkGlyph = 80.dp
    val checkEndOffset = 24.dp
    val checkBottomOffset = 28.dp
}

/**
 * Overview tab (Phase 13).
 *
 * Structure: page title → hero status card → scoped applications → secondary
 * page entries. All of it uses [PageSpacing] so the title and every card share
 * one gutter and one vertical rhythm, and every color still comes from
 * `MiuixTheme.colorScheme`.
 */
@Composable
fun OverviewScreen(
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
    isActive: Boolean = true,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(XposedState.UNAVAILABLE) }
    var scopeRequested by remember { mutableStateOf(false) }

    // Real versions of the scoped apps, read from their PackageInfo. Resolved off
    // the main thread so the page draws immediately.
    var scopeApps by remember { mutableStateOf<List<ScopeApp>>(emptyList()) }
    LaunchedEffect(context, lifecycleOwner, isActive) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                if (scopeApps.isEmpty()) {
                    scopeApps = withContext(Dispatchers.IO) {
                        ScopeApp.load(context, XposedState.REQUIRED_SCOPE)
                    }
                }
            }
            // The existing service callback drives refreshes; no polling or
            // Binder reads in composition, background pages or back previews.
            ConfigManager.serviceState.collectLatest { service ->
                repeat(6) { attempt ->
                    val snapshot = withContext(Dispatchers.IO) { XposedState.snapshot(service) }
                    status = snapshot
                    if (service == null || snapshot.bound || attempt == 5) return@collectLatest
                    // Preserve recovery from transient IPC failures, but never
                    // poll while waiting for an absent service to bind.
                    delay(500)
                }
            }
        }
    }

    PageContent(
        title = "MonoIcon",
        modifier = modifier,
        onTitleBottomPositioned = onTitleBottomPositioned,
    ) {
        Column {
            StatusHeroCard(
                status = status,
                modifier = Modifier.padding(horizontal = PageSpacing.gutter),
            )

            Spacer(modifier = Modifier.height(PageSpacing.sectionLabelGap))
            ScopeApplicationsCard(
                status = status,
                apps = scopeApps,
                scopeRequested = scopeRequested,
                onRequestScope = { missing ->
                    scopeRequested = requestScope(missing)
                },
            )

            // ── Secondary pages (one card each + chevron) ──
            Spacer(modifier = Modifier.height(PageSpacing.betweenCards))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = PageSpacing.gutter)) {
                NavigationRow(title = strings.logs, onClick = onOpenLogs)
            }
            Spacer(modifier = Modifier.height(PageSpacing.betweenCards))
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = PageSpacing.gutter)) {
                NavigationRow(title = strings.about, onClick = onOpenAbout)
            }
        }
    }
}

/**
 * Hero status card: the page's first visual element.
 *
 * Three text layers on a flattened full-width surface (status → framework
 * version → API level), with an oversized circle-check composition anchored to
 * the bottom-right corner. The check is an overlay child of the card [Box],
 * never a member of the text column, so the two layouts cannot influence each
 * other; the card's own shape clips it into the corner.
 */
@Composable
private fun StatusHeroCard(
    status: XposedState,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeroCard.minHeight),
        shape = HeroCard.shape,
        color = MiuixTheme.colorScheme.secondaryContainer,
        contentColor = MiuixTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(
                        start = PageSpacing.cardStart,
                        end = HeroCard.textEnd,
                        top = HeroCard.textVerticalPadding,
                        bottom = HeroCard.textVerticalPadding,
                    ),
            ) {
                // 1st layer — activation state: clearly the largest text of the card.
                Text(
                    text = if (status.active) strings.active else strings.inactive,
                    style = MiuixTheme.textStyles.headline1.copy(
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(HeroCard.versionGap))
                // 2nd layer — framework build.
                Text(
                    text = status.frameworkLabel,
                    style = MiuixTheme.textStyles.body1.copy(
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(HeroCard.apiGap))
                // 3rd layer — hook API level.
                Text(
                    text = "API ${status.apiVersion}",
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MiuixTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }

            // Both trailing edges extend past the card, leaving the check
            // visible while the rounded card clips the surrounding ring.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(
                        x = HeroCard.checkEndOffset,
                        y = HeroCard.checkBottomOffset,
                    )
                    .size(HeroCard.checkRing)
                    .border(
                        width = HeroCard.checkRingWidth,
                        color = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.22f),
                        shape = CircleShape,
                    )
                    .padding(HeroCard.checkRingPadding),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiuixIcons.Demibold.Ok,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.22f),
                    modifier = Modifier.size(HeroCard.checkGlyph),
                )
            }
        }
    }
}

/**
 * The applications MonoIcon is scoped to, each as name → package → version, plus
 * the framework's scope grant state. Replaces the old single-line scope rows
 * (which only listed package names) and the removed framework diagnostics.
 */
@Composable
private fun ScopeApplicationsCard(
    status: XposedState,
    apps: List<ScopeApp>,
    scopeRequested: Boolean,
    onRequestScope: (List<String>) -> Unit,
) {
    val strings = LocalStrings.current
    val missing = XposedState.REQUIRED_SCOPE.filterNot { status.hasScope(it) }

    SectionLabel(text = strings.scope)
    Spacer(modifier = Modifier.height(10.dp))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageSpacing.gutter),
        // Explicit inner margin: the rows below own their own heights so the
        // scope block keeps one stable vertical rhythm.
        insideMargin = PaddingValues(
            start = PageSpacing.cardStart,
            end = PageSpacing.cardStart,
            top = 20.dp,
            bottom = 20.dp,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            XposedState.REQUIRED_SCOPE.forEachIndexed { index, pkg ->
                ScopeAppRow(
                    name = when (pkg) {
                        "com.miui.home" -> strings.scopeAppLauncher
                        "com.android.systemui" -> strings.scopeAppSystemUi
                        else -> pkg
                    },
                    packageName = pkg,
                    versionLabel = apps.firstOrNull { it.packageName == pkg }
                        ?.versionLabel(strings.versionPrefix)
                        ?: ScopeApp.VERSION_UNAVAILABLE,
                    granted = status.hasScope(pkg),
                    grantedLabel = strings.scopeGranted,
                    missingLabel = strings.scopeMissing,
                )
                if (index != XposedState.REQUIRED_SCOPE.lastIndex) {
                    // Defaults only — no divider color/thickness of our own. It
                    // carries no padding, so the row rhythm stays the column's.
                    HorizontalDivider()
                }
            }
            // Only offered while the framework reports a package as unscoped.
            if (missing.isNotEmpty()) {
                BasicComponent(
                    title = if (scopeRequested) strings.scopeRequested else strings.requestScope,
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                    insideMargin = PaddingValues(
                        start = 0.dp,
                        end = 0.dp,
                        top = PageSpacing.cardVerticalPadding,
                        bottom = PageSpacing.cardVerticalPadding,
                    ),
                    onClick = { onRequestScope(missing) },
                )
            }
        }
    }
}

/** Section label above a card (`作用域`, …), indented like the page's own headings. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2.copy(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = PageSpacing.titleStart, end = PageSpacing.gutter),
    )
}

/**
 * One scoped application: name (first layer), package name (second), real
 * version (third), with the framework's grant state trailing the version row.
 *
 * The name and package are always rendered (they are known from the scope
 * list); only the version waits for the PackageManager query.
 */
@Composable
private fun ScopeAppRow(
    name: String,
    packageName: String,
    versionLabel: String,
    granted: Boolean,
    grantedLabel: String,
    missingLabel: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = name,
            style = MiuixTheme.textStyles.body1.copy(
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MiuixTheme.colorScheme.onSurfaceContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = packageName,
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = versionLabel,
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (granted) "✓ $grantedLabel" else "✗ $missingLabel",
                style = MiuixTheme.textStyles.footnote1.copy(
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                ),
                color = if (granted) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.error
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * One entry to a secondary page: a single full-height row with the shared card
 * padding, so 日志 and 关于 are exactly the same height and their chevrons line
 * up. Colors are unchanged (the component's own defaults plus the existing
 * `onSurfaceVariantSummary` chevron tint).
 */
@Composable
private fun NavigationRow(title: String, onClick: () -> Unit) {
    BasicComponent(
        title = title,
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        insideMargin = PaddingValues(
            start = PageSpacing.cardStart,
            end = PageSpacing.cardStart,
            top = 18.dp,
            bottom = 18.dp,
        ),
        endActions = {
            Icon(
                imageVector = MiuixIcons.Regular.ChevronForward,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        },
        onClick = onClick,
    )
}
