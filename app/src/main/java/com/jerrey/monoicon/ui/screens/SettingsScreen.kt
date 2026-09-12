package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassReducedMotionPolicy
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.GlassTransformPivot
import dev.chrisbanes.haze.glass.GlassTransformTarget
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Info
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
    OVERVIEW(MiuixIcons.Regular.Info),
    MODULE(MiuixIcons.Regular.Settings),
    ICON_STYLE(MiuixIcons.Regular.Edit),
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
                // Main tabs carry their own large left-aligned title (Liquid
                // Glass large-title pattern); only secondary pages need a bar.
                if (overlay != Overlay.NONE) {
                    SmallTopAppBar(
                        title = title,
                        navigationIcon = {
                            IconButton(onClick = { overlay = Overlay.NONE }) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Back,
                                    contentDescription = strings.back,
                                )
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

/**
 * Liquid-glass bottom navigation.
 *
 * Approximates Apple's Liquid Glass material with what Compose/Haze can do on
 * Android (Haze 1.7 has no lens refraction):
 *  - real backdrop blur of the content scrolling underneath, with a progressive
 *    fade at the bottom edge (HIG: "content scrolls under the bars with edge
 *    blur/fade");
 *  - palette-adaptive tint: two low-alpha tints derived from the Monet scheme;
 *  - specular edge highlight (bright at the top, falling off towards the
 *    bottom) plus an ambient shadow;
 *  - capsule shape (HIG: capsule controls, concentric radii);
 *  - interactive feedback: press scaling and a lighter selected "island".
 */
@OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)
@Composable
private fun GlassNavigationBar(
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    hazeState: HazeState,
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // One interaction source for the whole bar: it drives Glass's touch light
    // and the pressed transform.
    val barInteraction = remember { MutableInteractionSource() }
    val glassStyle = GlassStyle {
        shape(shape)
        tint(colors.surfaceContainer.copy(alpha = 0.28f))
        backgroundColor(colors.surfaceContainer.copy(alpha = 0.18f))
        // Real optics: light bends towards the edges (lensing) instead of a
        // uniform frosted blur.
        optics(
            refractionStrength = 0.65f,
            depth = 0.45f,
        )
        specularIntensity(if (dark) 0.30f else 0.45f)
        edgeSoftness(14.dp)
        edgeShadow(Color.Black.copy(alpha = if (dark) 0.20f else 0.08f))
        chromaticAberrationStrength(0.05f)
        interactionLightRadiusFraction(0.7f)
        pressed { scale(0.98f) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = shape, clip = false)
            .clip(shape)
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = glassStyle,
                performanceMode = HazePerformanceMode.Adaptive,
                expandLayerBounds = true,
                interactionSource = barInteraction,
                interactionTransformTarget = GlassTransformTarget.MaterialOnly,
                interactionTransformPivot = GlassTransformPivot.Pointer,
                interactionReducedMotionPolicy = GlassReducedMotionPolicy.System,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEachIndexed { index, entry ->
            val selected = entry == tab
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.90f else 1f,
                label = "navItemScale",
            )
            val color = if (selected) colors.primary else colors.onSurfaceVariantSummary

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent,
                    )
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { onTabChange(entry) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = labels.getOrNull(index),
                    tint = color,
                )
                Text(
                    text = labels.getOrNull(index).orEmpty(),
                    style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp),
                    color = color,
                )
            }
        }
    }
}
