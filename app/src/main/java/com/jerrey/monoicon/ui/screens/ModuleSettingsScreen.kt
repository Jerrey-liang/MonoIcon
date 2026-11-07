// Adapted from KernelSU SettingsMiuix (GPL-3.0-or-later), commit 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.safety.LauncherSafety
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.settings.AppUpdateChecker
import com.jerrey.monoicon.ui.settings.SendLogDialog
import com.jerrey.monoicon.ui.settings.UiPreferences
import com.jerrey.monoicon.ui.settings.rememberUiSettings
import com.jerrey.monoicon.ui.settings.settingsText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModuleSettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
    onOpenTheme: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = rememberUiSettings()
    var enabled by remember { mutableStateOf(ConfigManager.isEnabledFromUi()) }
    var safetyBlocked by remember { mutableStateOf(ConfigManager.isSafetyBlockedFromUi()) }
    var safetyBusy by remember { mutableStateOf(false) }
    var safetyError by remember { mutableStateOf(false) }
    val chinese = strings.masterSwitch == com.jerrey.monoicon.ui.stringsFor(AppLanguage.CHINESE, true).masterSwitch
    DisposableEffect(Unit) {
        val listener: (String?) -> Unit = {
            scope.launch {
                enabled = ConfigManager.isEnabledFromUi()
                safetyBlocked = ConfigManager.isSafetyBlockedFromUi()
            }
        }
        ConfigManager.addOnConfigChangedListener(listener)
        onDispose { ConfigManager.removeOnConfigChangedListener(listener) }
    }
    var restartHint by rememberSaveable { mutableStateOf(false) }
    var showSendLogDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            SwitchPreference(
                title = settingsText("settings_check_update"),
                summary = settingsText("settings_check_update_summary"),
                startAction = { SettingsIcon(Icons.Rounded.SystemUpdate) },
                checked = settings.checkUpdate,
                onCheckedChange = { value ->
                    UiPreferences.update { it.copy(checkUpdate = value) }
                    if (value) scope.launch { AppUpdateChecker.check(context, force = true) }
                },
            )
        }
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = settingsText("settings_theme"),
                summary = settingsText("settings_theme_summary"),
                startAction = { SettingsIcon(Icons.Rounded.Palette) },
                onClick = onOpenTheme,
            )
            OverlayDropdownPreference(
                title = strings.language,
                summary = strings.languageSummary,
                items = listOf(strings.languageSystem, strings.languageChinese, strings.languageEnglish),
                startAction = { SettingsIcon(Icons.Rounded.Language) },
                selectedIndex = language.ordinal,
                onSelectedIndexChange = { index ->
                    val option = AppLanguage.entries[index]
                    ConfigManager.setLanguage(option.id)
                    onLanguageChange(option)
                    logi("MonoIcon.Settings", "language=${option.id}")
                },
            )
        }
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            SwitchPreference(
                title = strings.masterSwitch,
                summary = if (safetyBlocked) {
                    if (chinese) "安全模式已自动停用模块：检测到桌面短命重启。重新开启主开关并重启作用域可重试。"
                    else "Safety mode disabled the module after an early launcher restart. Enable the master switch and restart the scope to retry."
                } else strings.masterSwitchSummary,
                startAction = { SettingsIcon(Icons.Rounded.PowerSettingsNew) },
                checked = enabled,
                enabled = !safetyBusy,
                onCheckedChange = { value ->
                    safetyBusy = true
                    safetyError = false
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                if (value) LauncherSafety.enable(context) else ConfigManager.setEnabled(false)
                            }
                            restartHint = true
                            logi("MonoIcon.Settings", "master switch=$value")
                        } catch (_: Exception) {
                            safetyError = true
                        } finally {
                            enabled = ConfigManager.isEnabledFromUi()
                            safetyBlocked = ConfigManager.isSafetyBlockedFromUi()
                            safetyBusy = false
                        }
                    }
                },
            )
            Text(
                text = if (chinese) "启动保护始终开启：启用后桌面未能存活 30 秒，将在下次启动时自动停用模块。"
                    else "Startup protection is always on: if the launcher exits within 30 seconds of activation, the module is disabled on the next start.",
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            )
            if (safetyError) Text(
                text = if (chinese) "无法安全恢复，请授予 MonoIcon root 权限后重试。" else "Unable to retry safely. Grant MonoIcon root access and try again.",
                color = MiuixTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
            )
        }
        Card(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth()) {
            ArrowPreference(
                title = settingsText("send_log"),
                startAction = { SettingsIcon(Icons.Rounded.BugReport) },
                onClick = { showSendLogDialog = true },
            )
            ArrowPreference(
                title = strings.about,
                startAction = { SettingsIcon(Icons.Rounded.Info) },
                onClick = onOpenAbout,
            )
        }
        if (restartHint) {
            Text(
                text = strings.restartHint,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
    SendLogDialog(
        show = showSendLogDialog,
        onDismissRequest = { showSendLogDialog = false },
        onOpenLogs = onOpenLogs,
    )
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp), tint = MiuixTheme.colorScheme.onBackground)
}
