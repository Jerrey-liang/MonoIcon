// Controls adapted from KernelSU SettingsMiuix and ColorPaletteScreenMiuix, GPL-3.0-or-later.
// Source revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Style
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.theme.color.dynamic.IconColorEngineId
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.logging.logi
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private const val TAG = "MonoIcon.Settings"

/**
 * Default source, appearance subpages, per-app settings and icon colours.
 * The shared top-bar power menu applies changes that require a scope restart.
 */
@Composable
fun IconStyleScreen(
    variant: String,
    onVariantChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
    onOpenLawniconsApps: () -> Unit = {},
    onOpenParameter: (IconStyleParameter) -> Unit = {},
) {
    val strings = LocalStrings.current
    val labels = rememberIconPreferencesStrings()
    var sourceStyle by remember { mutableStateOf(ConfigManager.getDefaultIconSourceStyleFromUi()) }
    var roundness by remember { mutableIntStateOf(ConfigManager.getIconRoundnessPercentFromUi()) }
    var iconSize by remember { mutableIntStateOf(ConfigManager.getIconSizePercentFromUi()) }
    var borderSize by remember { mutableIntStateOf((ConfigManager.getGeneratedIconBorderRatioFromUi() * 100f).roundToInt()) }
    var artworkRoundness by remember { mutableIntStateOf(ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()) }
    var artworkFollowRoundness by remember { mutableStateOf(ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi()) }
    var colorEngine by remember { mutableStateOf(ConfigManager.getColorEngineIdFromUi()) }
    var restartHint by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var allowLawniconsFallback by remember {
        mutableStateOf(ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides()))
    }
    DisposableEffect(Unit) {
        val listener: (String?) -> Unit = {
            scope.launch {
                sourceStyle = ConfigManager.getDefaultIconSourceStyleFromUi()
                roundness = ConfigManager.getIconRoundnessPercentFromUi()
                iconSize = ConfigManager.getIconSizePercentFromUi()
                borderSize = (ConfigManager.getGeneratedIconBorderRatioFromUi() * 100f).roundToInt()
                artworkRoundness = ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()
                artworkFollowRoundness = ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi()
                colorEngine = ConfigManager.getColorEngineIdFromUi()
                allowLawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides())
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }

    Column(modifier = modifier.padding(top = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(PageSpacing.betweenCards)) {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                OverlayDropdownPreference(
                    title = labels.defaultStyle,
                    summary = labels.styleSummary(sourceStyle, allowLawniconsFallback),
                    items = IconSourceStyle.entries.map(labels::styleName),
                    selectedIndex = IconSourceStyle.entries.indexOf(sourceStyle),
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.Style) },
                    modifier = Modifier.testTag("default-icon-style"),
                    onSelectedIndexChange = { index ->
                        sourceStyle = IconSourceStyle.entries[index]
                        restartHint = true
                        ConfigManager.setDefaultIconSourceStyle(sourceStyle)
                    },
                )
                ArrowPreference(
                    title = strings.iconRoundness,
                    summary = "$roundness%",
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.RoundedCorner) },
                    modifier = Modifier.testTag("open-icon-roundness"),
                    onClick = { onOpenParameter(IconStyleParameter.ROUNDNESS) },
                )
                ArrowPreference(
                    title = labels.artworkRoundness,
                    summary = if (artworkFollowRoundness) labels.artworkFollowingIcon else "$artworkRoundness% · ${labels.generatedBorderOnly}",
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.RoundedCorner) },
                    modifier = Modifier.testTag("open-artwork-roundness"),
                    onClick = { onOpenParameter(IconStyleParameter.ARTWORK_ROUNDNESS) },
                )
                ArrowPreference(
                    title = strings.iconSize,
                    summary = "$iconSize%",
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.AspectRatio) },
                    modifier = Modifier.testTag("open-icon-size"),
                    onClick = { onOpenParameter(IconStyleParameter.SIZE) },
                )
                ArrowPreference(
                    title = strings.iconBorderSize,
                    summary = "$borderSize% · ${labels.generatedBorderOnly}",
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.BorderOuter) },
                    modifier = Modifier.testTag("open-icon-border-size"),
                    onClick = { onOpenParameter(IconStyleParameter.BORDER) },
                )
                ArrowPreference(
                    title = labels.perAppTitle,
                    summary = labels.perAppSummary,
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.Apps) },
                    modifier = Modifier.testTag("open-app-icon-settings"),
                    onClick = onOpenLawniconsApps,
                )
            }

            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                val engines = IconColorEngineId.entries
                OverlayDropdownPreference(
                    title = strings.colorEngine,
                    summary = if (colorEngine == IconColorEngineId.MATERIAL_3.id)
                        strings.colorEngineMaterial3Summary else strings.colorEngine2025Summary,
                    items = engines.map { if (it == IconColorEngineId.MATERIAL_3)
                        strings.colorEngineMaterial3 else strings.colorEngine2025 },
                    selectedIndex = engines.indexOf(IconColorEngineId.fromId(colorEngine)),
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.DesignServices) },
                    modifier = Modifier.testTag("icon-color-engine"),
                    onSelectedIndexChange = { index ->
                        val id = engines[index].id
                        colorEngine = id
                        restartHint = true
                        ConfigManager.setColorEngineId(id)
                        logi(TAG, "colorEngine=$id")
                    },
                )
                val variants = Material2025ColorEngine.VariantId.entries
                OverlayDropdownPreference(
                    title = strings.colorVariant,
                    summary = strings.colorVariantSummary,
                    items = strings.colorVariants,
                    selectedIndex = variants.indexOf(Material2025ColorEngine.VariantId.fromId(variant)),
                    startAction = { IconStylePreferenceIcon(Icons.Rounded.Style) },
                    modifier = Modifier.testTag("icon-color-style"),
                    onSelectedIndexChange = { index ->
                        val id = variants[index].id
                        onVariantChange(id)
                        restartHint = true
                        ConfigManager.setVariantId(id)
                        logi(TAG, "variant=$id")
                    },
                )
            }

            AnimatedIconSettingsContent(visible = restartHint) {
                Text(
                    text = strings.restartHint,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = PageSpacing.gutter),
                )
            }
        }
    }
}

/** The style tab uses the same pinned preview host as its parameter subpages. */
@Composable
internal fun GlobalIconStylePreview(variant: String) {
    var defaults by remember { mutableStateOf(ConfigManager.getEffectiveIconStyleFromUi(null)) }
    var colorEngine by remember { mutableStateOf(ConfigManager.getColorEngineIdFromUi()) }
    var allowLawniconsFallback by remember {
        mutableStateOf(ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides()))
    }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val listener: (String?) -> Unit = { key ->
            scope.launch {
                defaults = ConfigManager.getEffectiveIconStyleFromUi(null)
                colorEngine = ConfigManager.getColorEngineIdFromUi()
                allowLawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides())
                if (key == null) revision++
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    IconStylePreview(
        roundnessPercent = defaults.roundnessPercent,
        sizePercent = defaults.sizePercent,
        colorEngineId = colorEngine,
        variantId = variant,
        lawniconsEnabled = defaults.style == IconSourceStyle.LAWNICONS,
        sourceStyle = defaults.style,
        allowLawniconsFallback = allowLawniconsFallback,
        configRevision = revision,
        generatedIconBorderRatio = defaults.generatedBorderRatio,
        generatedArtworkRadiusFraction = defaults.generatedArtworkRadiusFraction,
        modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(top = 12.dp, bottom = 12.dp),
    )
}

/** KernelSU's BasicComponent + bottomAction Slider layout, with explicit endpoints. */
@Composable
internal fun IconStyleSlider(
    title: String,
    summary: String,
    icon: ImageVector,
    value: Int,
    startLabel: String,
    endLabel: String,
    tag: String,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean = true,
) {
    BasicComponent(
        title = title,
        summary = summary,
        startAction = { IconStylePreferenceIcon(icon) },
        endActions = {
            Text("$value%", color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        },
        bottomAction = {
            Column {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.roundToInt().coerceIn(0, 100)) },
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = 0f..100f,
                    steps = 0,
                    enabled = enabled,
                    modifier = Modifier.testTag(tag).semantics {
                        contentDescription = title
                        if (!enabled) disabled()
                        // Miuix's accessibility action only calls onValueChange.
                        // An accessibility adjustment is a complete edit, not a drag.
                        setProgress { target ->
                            if (!enabled) return@setProgress false
                            onValueChange(target.roundToInt().coerceIn(0, 100))
                            onValueChangeFinished()
                            true
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(startLabel, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(endLabel, style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        },
    )
}

@Composable
internal fun IconStylePreferenceIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground)
}
