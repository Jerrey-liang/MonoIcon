package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.ModuleLogs
import com.jerrey.monoicon.ui.restartHyperOSLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Icon style tab (Phase 13): everything that changes how icons are drawn —
 * shape, Lawnicons tier, SystemUI notification icons, the Material 2025 colour
 * variant and the launcher restart needed to apply them.
 */
@Composable
fun IconStyleScreen(
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lawniconsEnabled by remember { mutableStateOf(ConfigManager.isLawniconsEnabledFromUi()) }
    var circleIconsEnabled by remember { mutableStateOf(ConfigManager.isCircleIconsEnabledFromUi()) }
    var notificationIconsEnabled by remember {
        mutableStateOf(ConfigManager.isNotificationIconsEnabledFromUi())
    }
    var selectedVariant by remember {
        mutableStateOf(
            Material2025ColorEngine.VariantId.fromId(ConfigManager.getVariantIdFromUi()).id
        )
    }
    var restarting by remember { mutableStateOf(false) }
    var restartHint by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Same large-title behaviour as the secondary pages: the host keeps its top bar
        // title hidden until this title has scrolled out of sight.
        if (onTitleBottomPositioned != null) {
            CollapsibleLargeTitle(text = strings.iconStyle, onBottomPositioned = onTitleBottomPositioned)
        } else {
            LargeScreenTitle(text = strings.iconStyle)
        }

        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
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
                            ModuleLogs.append("Settings", "circleIcons=$value")
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
                            ModuleLogs.append("Settings", "lawnicons=$value")
                        },
                    )
                },
            )
            BasicComponent(
                title = strings.notificationIcons,
                summary = strings.notificationIconsSummary,
                endActions = {
                    Switch(
                        checked = notificationIconsEnabled,
                        onCheckedChange = { value ->
                            notificationIconsEnabled = value
                            restartHint = true
                            ConfigManager.setNotificationIconsEnabled(value)
                            ModuleLogs.append("Settings", "notificationIcons=$value")
                        },
                    )
                },
            )
        }

        SmallTitle(text = strings.colorVariant)
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = strings.colorVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            Material2025ColorEngine.VariantId.entries.forEach { variant ->
                BasicComponent(
                    title = variant.label,
                    summary = if (variant.id == "tonal_spot") strings.variantDefaultHint else null,
                    endActions = {
                        Checkbox(
                            state = ToggleableState(selectedVariant == variant.id),
                            onClick = {
                                selectedVariant = variant.id
                                restartHint = true
                                ConfigManager.setVariantId(variant.id)
                                ModuleLogs.append("Settings", "variant=${variant.id}")
                            },
                        )
                    },
                    onClick = {
                        selectedVariant = variant.id
                        restartHint = true
                        ConfigManager.setVariantId(variant.id)
                        ModuleLogs.append("Settings", "variant=${variant.id}")
                    },
                )
            }
        }

        SmallTitle(text = strings.launcherRestart)
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.launcherRestart,
                summary = strings.launcherRestartSummary,
                endActions = {
                    Button(
                        onClick = {
                            if (restarting) return@Button
                            restarting = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { restartHyperOSLauncher(context) }
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) strings.restartDone else strings.restartFailed,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                                restarting = false
                            }
                        },
                        enabled = !restarting,
                    ) {
                        Text(text = if (restarting) strings.restarting else strings.restart)
                    }
                },
            )
        }

        if (restartHint) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = strings.restartHint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
