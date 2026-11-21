// Cards and preference layout follow KernelSU SettingsMiuix, GPL-3.0-or-later.
package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
    profileContent: @Composable () -> Unit,
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
                append("\n${word("图标处理管线", "Icon pipeline")}: ")
                append(when (environment.engine) {
                    LauncherEngine.JAVA -> "Java / ART"
                    LauncherEngine.NATIVE -> "Native / Profile"
                    LauncherEngine.UNKNOWN -> word("未知", "Unknown")
                })
                append("\nAndroid API ${environment.sdk} · ${environment.abi}")
                append("\n${word("模块", "Module")}: ${if (runtime.enabled) word("已启用", "Enabled") else word("已停用", "Disabled")}")
                if (runtime.safetyBlocked) append(" · ${word("安全保护已阻断", "Blocked by startup protection")}")
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

    runtime.processes.forEach { process ->
        Card(Modifier.padding(top = 12.dp).fillMaxWidth().testTag("configuration-process-${process.name}")) {
            BasicComponent(title = process.name,
                summary = buildString {
                    append(if (process.current) word("已收到本次运行报告", "Received a current runtime report")
                        else word("尚未收到本次运行报告，以下为历史记录或未知状态", "No current reply; entries below are historical or unknown"))
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
                            append(point.group)
                            if (point.member.isNotBlank()) append("\n${point.member}")
                            if (point.detail.isNotBlank()) append("\n${point.detail}")
                        },
                        endActions = { Text(status, color = color, style = MiuixTheme.textStyles.footnote1) })
                    val nativePoint = point.group.endsWith("-hook") || point.group == "profile-patch"
                    val fields = if (nativePoint && point.fields.none { it.first == "target" })
                        point.fields + ("target" to word("未解析", "Unresolved")) else point.fields
                    if (fields.isNotEmpty()) DiagnosticFields(fields, chinese)
                }
            }
        }
    }
    if (environment.engine == LauncherEngine.NATIVE) profileContent()
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
    if (" · " in key) return key.substringBefore(" · ") + " · " + fieldLabel(key.substringAfter(" · "), true)
    return mapOf("library" to "动态库", "offset" to "点位 RVA", "profileRva" to "Profile RVA",
        "target" to "目标地址", "loadBias" to "加载基址", "original" to "原值 / 原函数",
        "committed" to "最后提交值", "valueType" to "数值类型",
        "required" to "必需点位", "installerStopped" to "安装器已停止", "iconsEnabled" to "图标拦截已启用",
        "requestedProfileDigest" to "请求的 Profile 摘要", "foregroundPage" to "前景指令页",
        "reason" to "运行结果原因",
        "replacement" to "替换值 / 拦截函数", "observed" to "当前指令", "aliasOf" to "共用点位",
        "originPid" to "安装进程 PID", "resultCode" to "安装结果码", "profileId" to "运行 Profile",
        "profileDigest" to "运行 Profile 摘要", "phase" to "运行阶段", "result" to "事务结果码",
        "startTicks" to "进程启动标识", "apiVersion" to "Native API 版本", "hookAvailable" to "Hook API 可用",
        "unhookAvailable" to "Unhook API 可用", "spawner" to "Spawner 初始化", "child" to "子进程初始化",
        "sequence" to "初始化序号", "entryPoint" to "已解析入口地址", "path" to "映射路径",
        "fileOffset" to "文件映射偏移", "inode" to "文件 inode", "firstHitElapsedMs" to "首次触发（开机毫秒）")[key] ?: key
}

internal fun hookStatusText(status: String, hit: Boolean, chinese: Boolean): String {
    fun word(zh: String, en: String) = if (chinese) zh else en
    return when (status.lowercase()) {
        "installed" -> if (hit) word("已安装 · 已触发", "Installed · observed") else word("已安装 · 未触发", "Installed · not observed")
        "verified" -> word("已写入并验证", "Patched and verified")
        "alias" -> word("共用已安装拦截", "Installed alias")
        "failed", "hook-failed" -> word("安装失败", "Installation failed")
        "symbol-missing" -> word("符号未找到", "Symbol missing")
        "backup-missing" -> word("原函数缺失", "Original function missing")
        "mismatch" -> word("指令不一致", "Instruction mismatch")
        "read-failed" -> word("读取失败", "Read failed")
        "restored" -> word("已恢复原值", "Restored")
        "unchanged" -> word("保持原值", "Unchanged")
        "skipped" -> word("未启用 / 已跳过", "Disabled / skipped")
        "blocked" -> word("已阻断 / 未通过验证", "Blocked / unverified")
        "not-attempted" -> word("未尝试安装", "Not attempted")
        else -> word("未知 / 未上报", "Unknown / unreported")
    }
}
