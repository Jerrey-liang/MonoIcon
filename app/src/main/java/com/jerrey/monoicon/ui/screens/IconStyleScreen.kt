package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.logging.logi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "MonoIcon.Settings"

/**
 * Icon shape, Lawnicons and Material 2025 colour preferences.
 * The shared top-bar power menu applies changes that require a scope restart.
 */
@Composable
fun IconStyleScreen(
    variant: String,
    onVariantChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    var lawniconsEnabled by remember { mutableStateOf(ConfigManager.isLawniconsEnabledFromUi()) }
    var circleIconsEnabled by remember { mutableStateOf(ConfigManager.isCircleIconsEnabledFromUi()) }
    var restartHint by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(top = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(PageSpacing.betweenCards)) {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                BasicComponent(
                    title = strings.circleIcons,
                    summary = strings.circleIconsSummary,
                    endActions = {
                        Switch(
                            checked = circleIconsEnabled,
                            onCheckedChange = { value ->
                                circleIconsEnabled = value
                                restartHint = true
                                ConfigManager.setCircleIconsEnabled(value)
                                logi(TAG, "circleIcons=$value")
                            },
                        )
                    },
                )
                BasicComponent(
                    title = strings.lawnicons,
                    summary = strings.lawniconsSummary,
                    endActions = {
                        Switch(
                            checked = lawniconsEnabled,
                            onCheckedChange = { value ->
                                lawniconsEnabled = value
                                ConfigManager.setLawniconsEnabled(value)
                                logi(TAG, "lawnicons=$value")
                            },
                        )
                    },
                )
            }

            SmallTitle(text = strings.colorVariant)
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                Text(
                    text = strings.colorVariantSummary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(
                        start = PageSpacing.cardStart,
                        top = 12.dp,
                        bottom = 4.dp,
                    ),
                )
                Material2025ColorEngine.VariantId.entries.forEach { entry ->
                    BasicComponent(
                        title = entry.label,
                        summary = if (entry.id == "tonal_spot") strings.variantDefaultHint else null,
                        endActions = {
                            Checkbox(
                                state = ToggleableState(variant == entry.id),
                                onClick = {
                                    onVariantChange(entry.id)
                                    restartHint = true
                                    ConfigManager.setVariantId(entry.id)
                                    logi(TAG, "variant=${entry.id}")
                                },
                            )
                        },
                        onClick = {
                            onVariantChange(entry.id)
                            restartHint = true
                            ConfigManager.setVariantId(entry.id)
                            logi(TAG, "variant=${entry.id}")
                        },
                    )
                }
            }

            if (restartHint) {
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
