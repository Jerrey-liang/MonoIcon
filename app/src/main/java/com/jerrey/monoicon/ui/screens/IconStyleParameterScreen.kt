// Settings controls follow KernelSU's Miuix pages (GPL-3.0-or-later).
package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.BorderOuter
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.config.AppIconOverrides
import com.jerrey.monoicon.config.IconSourceStyle
import com.jerrey.monoicon.ui.AppStrings
import com.jerrey.monoicon.ui.LocalStrings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

enum class IconStyleParameter(val tag: String) {
    ROUNDNESS("icon-roundness"), SIZE("icon-size"), BORDER("icon-border-size"), ARTWORK_ROUNDNESS("artwork-roundness");

    fun title(strings: AppStrings) = when (this) {
        ROUNDNESS -> strings.iconRoundness
        SIZE -> strings.iconSize
        BORDER -> strings.iconBorderSize
        ARTWORK_ROUNDNESS -> if (strings == com.jerrey.monoicon.ui.stringsFor(com.jerrey.monoicon.ui.AppLanguage.CHINESE, false)) "图案圆角" else "Artwork roundness"
    }
}

/** The preview stays outside the scrolling controls, directly below the page title. */
@Composable
fun IconStyleParameterScreen(parameter: IconStyleParameter, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val words = rememberIconPreferencesStrings()
    var roundness by remember { mutableIntStateOf(ConfigManager.getIconRoundnessPercentFromUi()) }
    var size by remember { mutableIntStateOf(ConfigManager.getIconSizePercentFromUi()) }
    var border by remember { mutableIntStateOf((ConfigManager.getGeneratedIconBorderRatioFromUi() * 100).roundToInt()) }
    var artworkRoundness by remember { mutableIntStateOf(ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()) }
    var artworkFollowRoundness by remember { mutableStateOf(ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi()) }
    var sourceStyle by remember { mutableStateOf(ConfigManager.getDefaultIconSourceStyleFromUi()) }
    var colorEngine by remember { mutableStateOf(ConfigManager.getColorEngineIdFromUi()) }
    var variant by remember { mutableStateOf(ConfigManager.getVariantIdFromUi()) }
    var dragging by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var restartHint by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val allowLawniconsFallback = ConfigManager.permitsLawniconsFallbackFromUi(null, AppIconOverrides())
    DisposableEffect(parameter) {
        val listener: (String?) -> Unit = { key ->
            scope.launch {
                if (!dragging || parameter != IconStyleParameter.ROUNDNESS) roundness = ConfigManager.getIconRoundnessPercentFromUi()
                if (!dragging || parameter != IconStyleParameter.SIZE) size = ConfigManager.getIconSizePercentFromUi()
                if (!dragging || parameter != IconStyleParameter.BORDER) border = (ConfigManager.getGeneratedIconBorderRatioFromUi() * 100).roundToInt()
                if (!dragging || parameter != IconStyleParameter.ARTWORK_ROUNDNESS) artworkRoundness = ConfigManager.getGeneratedArtworkRoundnessPercentFromUi()
                artworkFollowRoundness = ConfigManager.getGeneratedArtworkFollowIconRoundnessFromUi()
                sourceStyle = ConfigManager.getDefaultIconSourceStyleFromUi()
                colorEngine = ConfigManager.getColorEngineIdFromUi()
                variant = ConfigManager.getVariantIdFromUi()
                if (key == null || key == "default_icon_source_style" || key == "lawnicons_enabled") revision++
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    KernelLazyPage(
        title = parameter.title(strings),
        onBack = onBack,
        modifier = Modifier.testTag("${parameter.tag}-page"),
        actions = { HomeTopBarActions() },
        pinnedContent = {
            IconStylePreview(
                roundnessPercent = roundness,
                sizePercent = size,
                colorEngineId = colorEngine,
                variantId = variant,
                lawniconsEnabled = sourceStyle == IconSourceStyle.LAWNICONS,
                sourceStyle = sourceStyle,
                allowLawniconsFallback = allowLawniconsFallback,
                configRevision = revision,
                generatedIconBorderRatio = border / 100f,
                generatedArtworkRadiusFraction = if (artworkFollowRoundness) 0.25f + roundness / 400f else artworkRoundness / 200f,
                generatedBorderOnly = parameter == IconStyleParameter.BORDER || parameter == IconStyleParameter.ARTWORK_ROUNDNESS,
                modifier = Modifier.padding(horizontal = PageSpacing.gutter).padding(top = 12.dp, bottom = 12.dp),
            )
        },
    ) {
        item {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                IconStyleSlider(
                    title = parameter.title(strings),
                    summary = when (parameter) {
                        IconStyleParameter.ROUNDNESS -> strings.iconRoundnessSummary
                        IconStyleParameter.SIZE -> strings.iconSizeSummary
                        IconStyleParameter.BORDER -> strings.iconBorderSizeSummary
                        IconStyleParameter.ARTWORK_ROUNDNESS -> if (artworkFollowRoundness) words.artworkFollowingIcon else words.artworkRoundnessSummary
                    },
                    icon = when (parameter) {
                        IconStyleParameter.ROUNDNESS -> Icons.Rounded.RoundedCorner
                        IconStyleParameter.SIZE -> Icons.Rounded.AspectRatio
                        IconStyleParameter.BORDER -> Icons.Rounded.BorderOuter
                        IconStyleParameter.ARTWORK_ROUNDNESS -> Icons.Rounded.RoundedCorner
                    },
                    value = when (parameter) {
                        IconStyleParameter.ROUNDNESS -> roundness
                        IconStyleParameter.SIZE -> size
                        IconStyleParameter.BORDER -> border
                        IconStyleParameter.ARTWORK_ROUNDNESS -> artworkRoundness
                    },
                    startLabel = when (parameter) {
                        IconStyleParameter.ROUNDNESS -> strings.iconSystemShape
                        IconStyleParameter.SIZE -> strings.iconMonoiconSize
                        IconStyleParameter.BORDER -> strings.iconNoBorder
                        IconStyleParameter.ARTWORK_ROUNDNESS -> words.artworkNoRoundness
                    },
                    endLabel = when (parameter) {
                        IconStyleParameter.ROUNDNESS -> strings.iconCircleShape
                        IconStyleParameter.SIZE -> strings.iconSystemSize
                        IconStyleParameter.BORDER -> strings.iconDefaultBorder
                        IconStyleParameter.ARTWORK_ROUNDNESS -> strings.iconCircleShape
                    },
                    tag = parameter.tag,
                    enabled = parameter != IconStyleParameter.ARTWORK_ROUNDNESS || !artworkFollowRoundness,
                    onValueChange = {
                        dragging = true
                        when (parameter) {
                            IconStyleParameter.ROUNDNESS -> roundness = it
                            IconStyleParameter.SIZE -> size = it
                            IconStyleParameter.BORDER -> border = it
                            IconStyleParameter.ARTWORK_ROUNDNESS -> artworkRoundness = it
                        }
                    },
                    onValueChangeFinished = {
                        dragging = false
                        when (parameter) {
                            IconStyleParameter.ROUNDNESS -> ConfigManager.setIconRoundnessPercent(roundness)
                            IconStyleParameter.SIZE -> ConfigManager.setIconSizePercent(size)
                            IconStyleParameter.BORDER -> ConfigManager.setGeneratedIconBorderRatio(border / 100f)
                            IconStyleParameter.ARTWORK_ROUNDNESS -> ConfigManager.setGeneratedArtworkRoundnessPercent(artworkRoundness)
                        }
                        restartHint = true
                    },
                )
                if (parameter == IconStyleParameter.ROUNDNESS) {
                    SwitchPreference(
                        title = words.artworkFollowIconRoundness,
                        summary = (if (artworkFollowRoundness) words.artworkFollowIconSummary else words.artworkIndependentSummary) + "\n${words.generatedBorderOnly}",
                        checked = artworkFollowRoundness,
                        startAction = { IconStylePreferenceIcon(Icons.Rounded.RoundedCorner) },
                        modifier = Modifier.testTag("artwork-follow-icon-roundness"),
                        onCheckedChange = {
                            artworkFollowRoundness = it
                            ConfigManager.setGeneratedArtworkFollowIconRoundness(it)
                            restartHint = true
                        },
                    )
                }
            }
        }
        item {
            AnimatedIconSettingsContent(visible = restartHint) {
                Text(strings.restartHint, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(PageSpacing.gutter))
            }
        }
    }
}
