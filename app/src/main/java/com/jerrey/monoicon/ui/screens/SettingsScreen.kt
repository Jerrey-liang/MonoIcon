package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.Material2025ColorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * MonoIcon settings screen, rebuilt with Miuix (MIUI/HyperOS design language)
 * on top of the Monet dynamic colour scheme from [MonoIconTheme].
 *
 * Layout follows the HyperOS settings convention: `SmallTitle` section headers
 * above `Card` groups of `BasicComponent` rows, with the switches as row
 * actions.
 *
 * All state still comes from / goes to [ConfigManager] (module remote
 * preferences); the hook processes pick changes up within one refresh interval
 * or on their next start.
 */
@Composable
fun SettingsScreen() {
    var enabled by remember { mutableStateOf(ConfigManager.isEnabledFromUi()) }
    var selectedVariant by remember {
        mutableStateOf(
            Material2025ColorEngine.VariantId
                .fromId(ConfigManager.getVariantIdFromUi())
                .id
        )
    }
    var showRestartHint by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var lawniconsEnabled by remember { mutableStateOf(ConfigManager.isLawniconsEnabledFromUi()) }
    var circleIconsEnabled by remember { mutableStateOf(ConfigManager.isCircleIconsEnabledFromUi()) }
    var notificationIconsEnabled by remember {
        mutableStateOf(ConfigManager.isNotificationIconsEnabledFromUi())
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            SmallTopAppBar(title = "MonoIcon")
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Module status ─────────────────────────────────────────
            SmallTitle(text = "模块状态")
            Card {
                BasicComponent(
                    title = "Monochrome Icons",
                    summary = "总开关：关闭后模块不处理任何图标",
                    rightActions = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                enabled = value
                                showRestartHint = true
                                ConfigManager.setEnabled(value)
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "Lawnicons 图标优先",
                    summary = "无 monochrome 层的应用使用内置 Lawnicons 字形",
                    rightActions = {
                        Switch(
                            checked = lawniconsEnabled,
                            onCheckedChange = { value ->
                                lawniconsEnabled = value
                                ConfigManager.setLawniconsEnabled(value)
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "圆形图标（无需主题）",
                    summary = "MIUI 图标与 MonoIcon 图标统一裁成圆形；修改后需重启桌面",
                    rightActions = {
                        Switch(
                            checked = circleIconsEnabled,
                            onCheckedChange = { value ->
                                circleIconsEnabled = value
                                showRestartHint = true
                                ConfigManager.setCircleIconsEnabled(value)
                            },
                        )
                    },
                )
                BasicComponent(
                    title = "通知中心图标",
                    summary = "通知栏程序图标使用 MonoIcon 图标；修改后需重启系统界面",
                    rightActions = {
                        Switch(
                            checked = notificationIconsEnabled,
                            onCheckedChange = { value ->
                                notificationIconsEnabled = value
                                showRestartHint = true
                                ConfigManager.setNotificationIconsEnabled(value)
                            },
                        )
                    },
                )
            }

            // ── Colour variant ────────────────────────────────────────
            SmallTitle(text = "Material Dynamic Color 2025")
            Card {
                Text(
                    text = "用于图标配色的取色方案",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                Material2025ColorEngine.VariantId.entries.forEach { variant ->
                    BasicComponent(
                        title = variant.label,
                        summary = if (variant.id == "tonal_spot") "默认 Material You 风格" else null,
                        rightActions = {
                            Checkbox(
                                checked = selectedVariant == variant.id,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedVariant = variant.id
                                        showRestartHint = true
                                        ConfigManager.setVariantId(variant.id)
                                    }
                                },
                            )
                        },
                        onClick = {
                            selectedVariant = variant.id
                            showRestartHint = true
                            ConfigManager.setVariantId(variant.id)
                        },
                    )
                }
            }

            // ── Launcher restart ──────────────────────────────────────
            SmallTitle(text = "重启桌面")
            Card {
                BasicComponent(
                    title = "重启 HyperOS 桌面",
                    summary = "重装模块或切换主题/形状后，重启桌面才能完全生效（首次需要授予 root）",
                    rightActions = {
                        Button(
                            onClick = {
                                if (restarting) return@Button
                                restarting = true
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        restartHyperOSLauncher(context)
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        if (ok) "已重启桌面" else "重启失败：请授予 MonoIcon root 权限",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                    restarting = false
                                }
                            },
                            enabled = !restarting,
                        ) {
                            Text(text = if (restarting) "重启中…" else "重启")
                        }
                    },
                )
            }

            if (showRestartHint) {
                Text(
                    text = "配置已修改，请重启桌面 / 系统界面以完全生效。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }

            // ── About ─────────────────────────────────────────────────
            SmallTitle(text = "关于")
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "MonoIcon 把 HyperOS 应用图标转换为单色 Material You 主题图标。",
                        style = MiuixTheme.textStyles.body2,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "作用域：com.miui.home（桌面 / 最近任务）· com.android.systemui（通知中心）",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Restarts the HyperOS launcher (com.miui.home).
 *
 * Primary path: `su` + `am force-stop` + `am start HOME` — deterministic on
 * rooted devices; falls back to asking the system to start HOME.
 *
 * @return true when the launcher was restarted (or at least killed).
 */
private fun restartHyperOSLauncher(context: android.content.Context): Boolean {
    val pkg = "com.miui.home"
    val commands = listOf(
        "am force-stop $pkg; " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME",
        "su -c 'am force-stop $pkg'; su -c 'am start -a android.intent.action.MAIN -c android.intent.category.HOME'",
    )
    for (cmd in commands) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) return true
        } catch (_: Throwable) {
            // try the next strategy
        }
    }
    return try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Throwable) {
        false
    }
}
