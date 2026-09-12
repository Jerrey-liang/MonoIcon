package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.stringsFor
import com.jerrey.monoicon.ui.systemPrefersChinese
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Back
import top.yukonga.miuix.kmp.icon.icons.useful.Edit
import top.yukonga.miuix.kmp.icon.icons.useful.Info
import top.yukonga.miuix.kmp.icon.icons.useful.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Settings host (Phase 13).
 *
 * Three Miuix tabs — 概览 / 模块设置 / 图标样式 — whose content scrolls behind a
 * floating **liquid-glass** bottom navigation bar (Haze backdrop blur + border
 * highlight). The overview tab opens the runtime-log and about pages as
 * secondary screens with a back arrow.
 */
private enum class MainTab(val icon: ImageVector) {
    OVERVIEW(MiuixIcons.Useful.Info),
    MODULE(MiuixIcons.Useful.Settings),
    ICON_STYLE(MiuixIcons.Useful.Edit),
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
        val hazeState = rememberHazeState()

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

        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = {
                        if (overlay != Overlay.NONE) {
                            IconButton(onClick = { overlay = Overlay.NONE }) {
                                Icon(
                                    imageVector = MiuixIcons.Useful.Back,
                                    contentDescription = strings.back,
                                )
                            }
                        }
                    },
                )
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
                        .hazeSource(hazeState)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 4.dp, bottom = 120.dp),
                ) {
                    when (overlay) {
                        Overlay.LOGS -> LogScreen()
                        Overlay.ABOUT -> AboutScreen()
                        Overlay.NONE -> when (tab) {
                            MainTab.OVERVIEW -> OverviewScreen(
                                onOpenLogs = { overlay = Overlay.LOGS },
                                onOpenAbout = { overlay = Overlay.ABOUT },
                            )

                            MainTab.MODULE -> ModuleSettingsScreen(
                                language = language,
                                onLanguageChange = { language = it },
                            )

                            MainTab.ICON_STYLE -> IconStyleScreen()
                        }
                    }
                }

                // ── Liquid-glass bottom navigation ─────────────────────
                if (overlay == Overlay.NONE) {
                    GlassNavigationBar(
                        tab = tab,
                        onTabChange = { tab = it },
                        hazeState = hazeState,
                        labels = listOf(strings.overview, strings.moduleSettings, strings.iconStyle),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationBar(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    hazeState: HazeState,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .hazeEffect(
                state = hazeState,
                style = HazeDefaults.style(
                    backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
                    blurRadius = 32.dp,
                    noiseFactor = 0.02f,
                ),
            )
            .border(
                width = 1.dp,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.12f),
                shape = shape,
            )
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEachIndexed { index, entry ->
            val selected = entry == tab
            val color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onTabChange(entry) }
                    .padding(horizontal = 18.dp, vertical = 4.dp)
                    .alpha(if (selected) 1f else 0.85f),
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = labels.getOrNull(index),
                    tint = color,
                )
                Text(
                    text = labels.getOrNull(index).orEmpty(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = color,
                )
            }
        }
    }
}
