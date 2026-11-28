// Cards and preference layout follow KernelSU SettingsMiuix, GPL-3.0-or-later.
package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.ui.LocalStrings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ConfigurationRuntimeContent(
    runtime: ConfigurationRuntime,
    loading: Boolean,
    error: String,
    onRefresh: () -> Unit,
    themeStatus: String,
    themeConflict: Boolean,
    onAcceptTheme: () -> Unit,
    canRecoverOriginal: Boolean,
    onRecoverOriginal: () -> Unit,
) {
    val chinese = LocalStrings.current.languageSystem == "跟随系统"
    fun word(zh: String, en: String) = if (chinese) zh else en
    val environment = runtime.environment
    Card(Modifier.padding(top = 12.dp).fillMaxWidth().testTag("configuration-environment")) {
        BasicComponent(
            title = word("运行环境", "Runtime environment"),
            summary = buildString {
                append(environment.system.ifBlank { word("系统版本未知", "System version unavailable") })
                append("\n${word("系统桌面", "Launcher")}: ${environment.launcher.ifBlank { "—" }}")
                append("\n${word("桌面类型", "Launcher type")}: ")
                append(when (environment.engine) {
                    LauncherEngine.JAVA -> "Java / ART"
                    LauncherEngine.NATIVE -> word("原生", "Native")
                    LauncherEngine.UNKNOWN -> word("未知", "Unknown")
                })
                append("\nAndroid API ${environment.sdk}")
                append("\n${word("处理器架构", "ABI")}: ${environment.abi}")
                append("\n${word("模块", "Module")}: ${if (runtime.enabled) word("已启用", "Enabled") else word("已停用", "Disabled")}")
                if (runtime.safetyBlocked) append("\n${word("启动保护已停用模块", "Disabled by startup protection")}")
            },
        )
        ArrowPreference(
            title = word("刷新运行信息", "Refresh runtime information"),
            summary = if (loading) word("正在请求宿主进程状态…", "Requesting host process reports…")
                else word("查看当前运行的 Hook 安装与触发情况", "See which hooks are installed and which have run"),
            startAction = { Icon(Icons.Rounded.Refresh, null, Modifier.padding(end = 6.dp),
                tint = MiuixTheme.colorScheme.onBackground) },
            enabled = !loading,
            onClick = onRefresh,
        )
        if (error.isNotBlank()) Text(error, color = MiuixTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
    }

    if (environment.isHyperOs4) {
        Card(Modifier.padding(top = 12.dp).fillMaxWidth().testTag("hyperos4-icon-compatibility")) {
            BasicComponent(
                title = word("系统主题图标", "System theme icons"),
                summary = if (environment.themeArchiveAvailable) themeStatusText(themeStatus, chinese)
                    else word("当前 Android 用户或运行环境不支持主题归档", "Theme archive unavailable in this Android user or environment"),
            )
            if (environment.themeArchiveAvailable && canRecoverOriginal) {
                ArrowPreference(
                    title = word("恢复原主题图标", "Restore original theme icons"),
                    summary = word("从备份恢复图标，并关闭 MonoIcon", "Restore icons from the backup and turn off MonoIcon"),
                    startAction = { Icon(Icons.Rounded.Restore, null, Modifier.padding(end = 6.dp),
                        tint = MiuixTheme.colorScheme.onBackground) },
                    onClick = onRecoverOriginal,
                )
            }
            if (environment.themeArchiveAvailable && runtime.enabled && themeConflict && !canRecoverOriginal) {
                ArrowPreference(
                    title = word("在当前主题上应用 MonoIcon", "Apply MonoIcon to the current theme"),
                    summary = word("停用 MonoIcon 时将恢复到这个主题", "Disabling MonoIcon will restore this theme"),
                    startAction = { Icon(Icons.Rounded.Wallpaper, null, Modifier.padding(end = 6.dp),
                        tint = MiuixTheme.colorScheme.onBackground) },
                    onClick = onAcceptTheme,
                )
            }
        }
    }

    runtime.processes.forEach { process ->
        Card(Modifier.padding(top = 12.dp).fillMaxWidth().testTag("configuration-process-${process.name}")) {
            BasicComponent(title = process.name,
                summary = buildString {
                    append(if (process.current) word("已收到本次运行报告", "Received a current runtime report")
                        else word("尚未收到本次运行报告，以下为历史记录或未知状态", "No current reply. Entries below are historical or unknown."))
                    process.pid?.let { append("\nPID $it") }
                    if (process.capturedAtMillis > 0) append("\n${word("上报时间", "Reported at")}: " +
                        DateFormat.getDateTimeInstance().format(Date(process.capturedAtMillis)))
                    if (process.detail.isNotBlank()) append("\n${process.detail}")
                })
            process.points.forEach { point ->
                val status = (if (!process.current && point.status.lowercase() != "unknown")
                    word("历史：", "Previous: ") else "") + hookStatusText(point.status, point.hit, chinese)
                val bad = point.status.equals("failed", ignoreCase = true)
                val successful = point.status.equals("installed", ignoreCase = true)
                val color = when {
                    !process.current -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    bad -> MiuixTheme.colorScheme.error
                    successful -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                Column(Modifier.testTag("configuration-hook-${process.name}-${point.group}-${point.id}")) {
                    BasicComponent(title = point.id,
                        summary = buildString {
                            append(point.group)
                            if (point.member.isNotBlank() && point.member != point.id) append("\n${point.member}")
                            if (point.detail.isNotBlank()) append("\n${point.detail}")
                        },
                        endActions = { Text(status, color = color, style = MiuixTheme.textStyles.footnote1) })
                    if (point.fields.isNotEmpty()) DiagnosticFields(point.fields, chinese)
                }
            }
        }
    }
}

private fun themeStatusText(status: String, chinese: Boolean): String {
    fun word(zh: String, en: String) = if (chinese) zh else en
    val summary = when (status.substringBefore('\n')) {
        "active" -> word("主题图标已同步", "Theme icons synced")
        "inactive" -> word("当前未应用 MonoIcon 主题图标", "No MonoIcon theme icons are applied")
        "conflict" -> word("系统主题图标已变化，MonoIcon 已暂停同步", "System theme icons changed. MonoIcon paused syncing.")
        "rejected" -> word("系统授权校验拒绝了主题归档。原图标已恢复，普通图标同步已暂停。",
            "HyperOS rejected the archive. Original icons were restored and icon syncing is paused.")
        "refresh" -> word("主题归档已更新，正在等待系统刷新", "Theme archive updated. Waiting for the system to refresh.")
        "error" -> word("主题图标同步失败", "Theme icon sync failed")
        else -> word("正在等待主题图标同步", "Waiting to sync theme icons")
    }
    return listOf(summary, status.substringAfter('\n', "")).filter(String::isNotBlank).joinToString("\n")
}

@Composable
private fun DiagnosticFields(fields: List<Pair<String, String>>, chinese: Boolean) {
    SelectionContainer {
        Text(fields.joinToString("\n") { (name, value) -> "${fieldLabel(name, chinese)}: $value" },
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
    }
}

private fun fieldLabel(key: String, chinese: Boolean): String {
    if (!chinese) return key
    return when (key) {
        "required" -> "必需点位"
        "firstHitElapsedMs" -> "首次触发（开机毫秒）"
        else -> key
    }
}

internal fun hookStatusText(status: String, hit: Boolean?, chinese: Boolean): String {
    fun word(zh: String, en: String) = if (chinese) zh else en
    return when (status.lowercase()) {
        "installed" -> when (hit) {
            true -> word("已安装并触发", "Installed and observed")
            false -> word("已安装，尚未触发", "Installed, awaiting first hit")
            null -> word("已安装", "Installed")
        }
        "failed" -> word("安装失败", "Installation failed")
        "skipped" -> word("未启用 / 已跳过", "Disabled / skipped")
        else -> word("未知 / 未上报", "Unknown / unreported")
    }
}
