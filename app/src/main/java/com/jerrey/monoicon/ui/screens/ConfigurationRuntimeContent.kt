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
                else word("逐点报告来自桌面和 SystemUI 的实际运行进程", "Point reports come from the actual launcher and SystemUI processes"),
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
            BasicComponent(
                title = word("大图标支持", "Large icon support"),
                summary = when (runtime.nativeExtensionMatched) {
                    true -> word("当前桌面匹配内置大图标支持", "This launcher matches built-in large icon support")
                    false -> word("当前桌面未匹配内置大图标支持", "This launcher has no matching built-in large icon support")
                    null -> word("尚未确认桌面兼容性", "Launcher compatibility pending")
                },
            )
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
            if (process.fields.isNotEmpty()) DiagnosticFields(process.fields, chinese)
            process.points.forEach { point ->
                val status = (if (!process.current && point.status.lowercase() != "unknown")
                    word("历史：", "Previous: ") else "") + hookStatusText(point.status, point.hit, chinese)
                val bad = point.status.lowercase() in setOf("failed", "hook-failed", "backup-missing", "mismatch", "read-failed", "symbol-missing")
                val successful = point.status.lowercase() in setOf("installed", "verified", "alias")
                val color = when {
                    !process.current -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    bad -> MiuixTheme.colorScheme.error
                    successful -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
                Column(Modifier.testTag("configuration-hook-${process.name}-${point.group}-${point.id}")) {
                    BasicComponent(title = point.id,
                        summary = buildString {
                            append(when (point.group) {
                                "startup-hook" -> word("启动", "Startup")
                                "libc-hook" -> word("大图标文件读取", "Large icon file access")
                                else -> point.group
                            })
                            if (point.member.isNotBlank() && point.member != point.id) append("\n${point.member}")
                            if (point.detail.isNotBlank()) append("\n${point.detail}")
                        },
                        endActions = { Text(status, color = color, style = MiuixTheme.textStyles.footnote1) })
                    val nativePoint = point.group == "startup-hook" || point.group == "libc-hook"
                    val fields = if (nativePoint && point.fields.none { it.first == "target" })
                        point.fields + ("target" to word("未解析", "Unresolved")) else point.fields
                    if (fields.isNotEmpty()) DiagnosticFields(fields, chinese)
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
    if (!chinese) return when (key) {
        "iconsEnabled" -> "Large icon file access enabled"
        "profileId" -> "Large icon configuration"
        "profileDigest" -> "Large icon configuration digest"
        "requestedProfileDigest" -> "Requested large icon configuration digest"
        else -> key
    }
    if (" / " in key) return key.substringBefore(" / ") + " / " + fieldLabel(key.substringAfter(" / "), true)
    return mapOf("library" to "动态库", "offset" to "函数偏移",
        "target" to "目标地址", "loadBias" to "加载基址", "original" to "原函数",
        "required" to "必需点位", "installerStopped" to "安装器已停止", "iconsEnabled" to "大图标文件读取已启用",
        "requestedProfileDigest" to "请求的大图标配置摘要",
        "reason" to "运行结果原因",
        "replacement" to "拦截函数", "aliasOf" to "共用点位",
        "originPid" to "安装进程 PID", "resultCode" to "安装结果码", "profileId" to "运行的大图标配置",
        "profileDigest" to "大图标配置摘要", "phase" to "运行阶段", "result" to "运行结果码",
        "startTicks" to "进程启动标识", "apiVersion" to "Native API 版本", "hookAvailable" to "Hook API 可用",
        "unhookAvailable" to "Unhook API 可用", "spawner" to "Spawner 初始化", "child" to "子进程初始化",
        "sequence" to "初始化序号", "entryPoint" to "已解析入口地址", "path" to "映射路径",
        "fileOffset" to "文件映射偏移", "inode" to "文件 inode", "firstHitElapsedMs" to "首次触发（开机毫秒）")[key] ?: key
}

internal fun hookStatusText(status: String, hit: Boolean?, chinese: Boolean): String {
    fun word(zh: String, en: String) = if (chinese) zh else en
    return when (status.lowercase()) {
        "installed" -> when (hit) {
            true -> word("已安装并触发", "Installed and observed")
            false -> word("已安装，尚未触发", "Installed, awaiting first hit")
            null -> word("已安装", "Installed")
        }
        "verified" -> word("已验证", "Verified")
        "alias" -> word("共用已安装拦截", "Installed alias")
        "failed", "hook-failed" -> word("安装失败", "Installation failed")
        "symbol-missing" -> word("符号未找到", "Symbol missing")
        "backup-missing" -> word("原函数缺失", "Original function missing")
        "mismatch" -> word("校验不一致", "Verification mismatch")
        "read-failed" -> word("读取失败", "Read failed")
        "skipped" -> word("未启用 / 已跳过", "Disabled / skipped")
        "blocked" -> word("已阻断 / 未通过验证", "Blocked / unverified")
        "not-attempted" -> word("未尝试安装", "Not attempted")
        else -> word("未知 / 未上报", "Unknown / unreported")
    }
}
