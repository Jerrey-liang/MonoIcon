// Adapted from KernelSU ColorPaletteScreenMiuix (GPL-3.0-or-later), commit 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

import com.jerrey.monoicon.ui.settings.*
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.ThemeColorSpec

@Composable
fun ThemeSettingsScreen(modifier: Modifier = Modifier) {
    val uiState = rememberUiSettings()
    val activity = LocalActivity.current
    val actions = remember(activity) { ThemeActions(activity) }
    val showScaleDialog = rememberSaveable { mutableStateOf(false) }
    val isDark = isManagerDarkTheme()
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        Spacer(modifier = Modifier.height(32.dp))
        ThemePreviewCardMiuix(
            keyColor = uiState.keyColor,
            isDark = isDark,
            miuixMonet = uiState.monet,
            enableFloatingBottomBar = uiState.floatingBottomBar,
            enableFloatingBottomBarBlur = uiState.glassBottomBar,
            paletteStyle = ThemePaletteStyle.entries.firstOrNull { it.name == uiState.colorStyle } ?: ThemePaletteStyle.TonalSpot,
            colorSpec = ThemeColorSpec.entries.firstOrNull { it.name == uiState.colorSpec } ?: ThemeColorSpec.Spec2025,
        )
        Spacer(modifier = Modifier.height(72.dp))

        val themeItems = listOf(
            settingsText("settings_theme_mode_system"),
            settingsText("settings_theme_mode_light"),
            settingsText("settings_theme_mode_dark"),
        )
        TabRow(
            tabs = themeItems,
            selectedTabIndex = (if (uiState.themeMode >= 3) uiState.themeMode - 3 else uiState.themeMode).coerceIn(0, 2),
            onTabSelected = { index ->
                actions.onSetThemeMode(index)
            },
        )

        Card(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
        ) {
            SwitchPreference(
                title = settingsText("settings_monet"),
                startAction = {
                    Icon(
                        Icons.Rounded.Wallpaper,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_monet"),
                        tint = colorScheme.onBackground
                    )
                },
                checked = uiState.monet,
                onCheckedChange = {
                    actions.onSetMiuixMonet(it)
                }
            )

            AnimatedVisibility(
                visible = uiState.monet
            ) {
                Column {
                    val colorItems = listOf(
                        settingsText("settings_key_color_default"),
                        settingsText("color_red"),
                        settingsText("color_pink"),
                        settingsText("color_purple"),
                        settingsText("color_deep_purple"),
                        settingsText("color_indigo"),
                        settingsText("color_blue"),
                        settingsText("color_cyan"),
                        settingsText("color_teal"),
                        settingsText("color_green"),
                        settingsText("color_yellow"),
                        settingsText("color_amber"),
                        settingsText("color_orange"),
                        settingsText("color_brown"),
                        settingsText("color_blue_grey"),
                        settingsText("color_sakura"),
                    )
                    val colorValues = listOf(0) + keyColorOptions
                    OverlayDropdownPreference(
                        title = settingsText("settings_key_color"),
                        items = colorItems,
                        startAction = {
                            Icon(
                                Icons.Rounded.Colorize,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = settingsText("settings_key_color"),
                                tint = colorScheme.onBackground
                            )
                        },
                        selectedIndex = colorValues.indexOf(uiState.keyColor).takeIf { it >= 0 } ?: 0,
                        onSelectedIndexChange = { index ->
                            actions.onSetKeyColor(colorValues[index])
                        }
                    )

                    AnimatedVisibility(
                        visible = uiState.keyColor != 0
                    ) {
                        Column {
                            val styles = ThemePaletteStyle.entries
                            OverlayDropdownPreference(
                                title = settingsText("settings_color_style"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Style,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = settingsText("settings_color_style"),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                items = styles.map { it.name },
                                selectedIndex = styles.indexOfFirst { it.name == uiState.colorStyle }.coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    actions.onSetColorStyle(styles[index].name)
                                }
                            )

                            val specs = ThemeColorSpec.entries
                            OverlayDropdownPreference(
                                title = settingsText("settings_color_spec"),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.DesignServices,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = settingsText("settings_color_spec"),
                                        tint = colorScheme.onBackground
                                    )
                                },
                                items = specs.map { it.name },
                                selectedIndex = specs.indexOfFirst { it.name == uiState.colorSpec }.coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    actions.onSetColorSpec(specs[index].name)
                                }
                            )
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SwitchPreference(
                    title = settingsText("settings_enable_blur"),
                    summary = settingsText("settings_enable_blur_summary"),
                    startAction = {
                        Icon(
                            Icons.Rounded.BlurOn,
                            modifier = Modifier.padding(end = 6.dp),
                            contentDescription = settingsText("settings_enable_blur"),
                            tint = colorScheme.onBackground
                        )
                    },
                    checked = uiState.enableBlur,
                    onCheckedChange = {
                        actions.onSetEnableBlur(it)
                    }
                )
            }
            SwitchPreference(
                title = settingsText("settings_floating_bottom_bar"),
                summary = settingsText("settings_floating_bottom_bar_summary"),
                startAction = {
                    Icon(
                        Icons.Rounded.CallToAction,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_floating_bottom_bar"),
                        tint = colorScheme.onBackground
                    )
                },
                checked = uiState.floatingBottomBar,
                onCheckedChange = {
                    actions.onSetEnableFloatingBottomBar(it)
                }
            )
            AnimatedVisibility(visible = uiState.floatingBottomBar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SwitchPreference(
                    title = settingsText("settings_enable_glass"),
                    summary = settingsText("settings_enable_glass_summary"),
                    startAction = {
                        Icon(
                            Icons.Rounded.WaterDrop,
                            modifier = Modifier.padding(end = 6.dp),
                            contentDescription = settingsText("settings_enable_glass"),
                            tint = colorScheme.onBackground
                        )
                    },
                    checked = uiState.glassBottomBar,
                    onCheckedChange = {
                        actions.onSetEnableFloatingBottomBarBlur(it)
                    }
                )
            }
            SwitchPreference(
                title = settingsText("settings_navigation_badge"),
                summary = settingsText("settings_navigation_badge_summary"),
                startAction = {
                    Icon(
                        Icons.Rounded.Pin,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_navigation_badge"),
                        tint = colorScheme.onBackground
                    )
                },
                checked = uiState.navigationBadge,
                onCheckedChange = {
                    actions.onSetEnableNavigationBadge(it)
                }
            )
        }

        Card(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                SwitchPreference(
                    title = settingsText("settings_enable_predictive_back"),
                    summary = settingsText("settings_enable_predictive_back_summary"),
                    startAction = {
                        Icon(
                            Icons.AutoMirrored.Rounded.MenuOpen,
                            modifier = Modifier.padding(end = 6.dp),
                            contentDescription = settingsText("settings_enable_predictive_back"),
                            tint = colorScheme.onBackground
                        )
                    },
                    checked = uiState.predictiveBack,
                    onCheckedChange = {
                        actions.onSetEnablePredictiveBack(it)
                    }
                )
            }
            SwitchPreference(
                title = settingsText("settings_enable_swipe_dismiss"),
                summary = settingsText("settings_enable_swipe_dismiss_summary"),
                startAction = {
                    Icon(
                        Icons.Rounded.Swipe,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_enable_swipe_dismiss"),
                        tint = colorScheme.onBackground,
                    )
                },
                checked = uiState.swipeDismiss,
                onCheckedChange = actions::onSetEnableSwipeDismiss,
            )
            OverlayDropdownPreference(
                title = settingsText("settings_pager_gesture_mode"),
                items = listOf(
                    settingsText("settings_pager_gesture_native"),
                    settingsText("settings_pager_gesture_cross_axis"),
                    settingsText("settings_pager_gesture_ios_like"),
                ),
                startAction = {
                    Icon(
                        Icons.Rounded.ViewCarousel,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_pager_gesture_mode"),
                        tint = colorScheme.onBackground,
                    )
                },
                selectedIndex = uiState.pagerGestureMode.coerceIn(0, 2),
                onSelectedIndexChange = actions::onSetPagerInterceptionMode,
            )

            var sliderValue by remember(uiState.pageScale) { mutableFloatStateOf(uiState.pageScale) }
            ArrowPreference(
                title = settingsText("settings_page_scale"),
                summary = settingsText("settings_page_scale_summary"),
                startAction = {
                    Icon(
                        Icons.Rounded.AspectRatio,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = settingsText("settings_page_scale"),
                        tint = colorScheme.onBackground
                    )
                },
                endActions = {
                    Text(
                        text = "${(sliderValue * 100).toInt()}%",
                        color = colorScheme.onSurfaceVariantActions,
                    )
                },
                onClick = { showScaleDialog.value = !showScaleDialog.value },
                holdDownState = showScaleDialog.value,
                bottomAction = {
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            actions.onSetPageScale(sliderValue)
                        },
                        valueRange = 0.8f..1.1f,
                        showKeyPoints = true,
                        keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                        magnetThreshold = 0.01f,
                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    )
                },
            )
            ScaleDialog(
                show = showScaleDialog.value,
                onDismissRequest = { showScaleDialog.value = false },
                volumeState = { uiState.pageScale },
                onVolumeChange = {
                    actions.onSetPageScale(it)
                }
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ThemePreviewCardMiuix(
    keyColor: Int,
    isDark: Boolean,
    miuixMonet: Boolean,
    enableFloatingBottomBar: Boolean = false,
    enableFloatingBottomBarBlur: Boolean = false,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TonalSpot,
    colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2021,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val screenRatio = screenWidth / screenHeight
    val useRail = screenWidth >= 840f && !enableFloatingBottomBar

    // The surrounding manager theme updates immediately with the preferences,
    // so the preview uses exactly the palette currently visible in the app.
    val bgColor = colorScheme.surface
    val textColor = colorScheme.onBackground
    val accentCardColor = if (miuixMonet) colorScheme.secondaryContainer else if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    val cardColor = colorScheme.surfaceContainerHighest
    val navBarColor = colorScheme.surfaceContainer
    val iconColor = colorScheme.primary
    val navSelectedColor = colorScheme.onSurfaceContainer
    val navUnselectedColor = colorScheme.onSurfaceContainer.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(screenRatio)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .border(1.dp, colorScheme.outline, RoundedCornerShape(20.dp))
        ) {
            val content = @Composable {
                Column {
                    Row(
                        modifier = Modifier
                            .height(if (useRail) 36.dp else 48.dp)
                            .fillMaxWidth()
                            .padding(start = 12.dp, top = if (useRail) 12.dp else 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = settingsText("app_name"),
                            fontSize = 12.sp,
                            color = textColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(45.dp)
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentCardColor)
                    )

                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        val smallCardHeight = 12.dp
                        val smallCardCount = when {
                            maxHeight >= 96.dp -> 2
                            maxHeight >= 72.dp -> 1
                            else -> 0
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(cardColor)
                            )
                            repeat(smallCardCount) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(smallCardHeight)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(cardColor)
                                )
                            }
                        }
                    }
                }
            }

            if (useRail) {
                Row {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(30.dp)
                            .background(navBarColor),
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) navSelectedColor else navUnselectedColor)
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f))
                    )
                    Box(modifier = Modifier.weight(1f)) { content() }
                }
            } else {
                content()
            }

            if (!useRail && enableFloatingBottomBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (enableFloatingBottomBarBlur) navBarColor.copy(alpha = 0.5f)
                                else navBarColor
                            )
                            .border(0.5.dp, textColor.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (it == 0) iconColor else textColor)
                            )
                        }
                    }
                }
            } else if (!useRail) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f))
                    )
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .fillMaxWidth()
                            .background(navBarColor)
                            .padding(top = 2.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(4) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) navSelectedColor else navUnselectedColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

private class ThemeActions(private val activity: android.app.Activity?) {
    fun onSetThemeMode(value: Int) = UiPreferences.update { it.copy(themeMode = value) }
    fun onSetMiuixMonet(value: Boolean) = UiPreferences.update { it.copy(monet = value) }
    fun onSetKeyColor(value: Int) = UiPreferences.update { it.copy(keyColor = value) }
    fun onSetColorStyle(value: String) = UiPreferences.update { it.copy(colorStyle = value) }
    fun onSetColorSpec(value: String) = UiPreferences.update { it.copy(colorSpec = value) }
    fun onSetEnableBlur(value: Boolean) = UiPreferences.update { it.copy(enableBlur = value) }
    fun onSetEnableFloatingBottomBar(value: Boolean) = UiPreferences.update { it.copy(floatingBottomBar = value) }
    fun onSetEnableFloatingBottomBarBlur(value: Boolean) = UiPreferences.update { it.copy(glassBottomBar = value) }
    fun onSetEnableNavigationBadge(value: Boolean) = UiPreferences.update { it.copy(navigationBadge = value) }
    fun onSetEnablePredictiveBack(value: Boolean) {
        UiPreferences.update { it.copy(predictiveBack = value) }
        // Recreate the dispatcher so this preference takes effect immediately;
        // the navigation back stack and current page are saveable.
        if (activity != null && PredictiveBackPreference.apply(activity, value)) activity.recreate()
    }
    fun onSetEnableSwipeDismiss(value: Boolean) = UiPreferences.update { it.copy(swipeDismiss = value) }
    fun onSetPagerInterceptionMode(value: Int) = UiPreferences.update { it.copy(pagerGestureMode = value) }
    fun onSetPageScale(value: Float) = UiPreferences.update { it.copy(pageScale = value) }
}

private val keyColorOptions = listOf(0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF00BCD4, 0xFF009688, 0xFF4FAF50, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFF795548, 0xFF607D8F, 0xFFFF9CA8).map { it.toInt() }
