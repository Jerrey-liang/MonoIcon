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
import com.jerrey.monoicon.ui.AppLanguage
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.ModuleLogs
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Module settings tab (Phase 13): master switch and UI language.
 *
 * Uses the shared [PageContent] host, so the page title sits at exactly the same
 * place and distance from the first card as on every other primary page.
 *
 * The language selection is applied to the Activity configuration in
 * [com.jerrey.monoicon.MainActivity.attachBaseContext]; the composition updates
 * immediately through [onLanguageChange].
 */
@Composable
fun ModuleSettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
    onTitleBottomPositioned: ((Int) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    var enabled by remember { mutableStateOf(ConfigManager.isEnabledFromUi()) }
    var restartHint by remember { mutableStateOf(false) }

    PageContent(
        title = strings.moduleSettings,
        modifier = modifier,
        onTitleBottomPositioned = onTitleBottomPositioned,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PageSpacing.betweenCards)) {
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                BasicComponent(
                    title = strings.masterSwitch,
                    summary = strings.masterSwitchSummary,
                    endActions = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                enabled = value
                                restartHint = true
                                ConfigManager.setEnabled(value)
                                ModuleLogs.append("Settings", "master switch=$value")
                            },
                        )
                    },
                )
            }

            SmallTitle(text = strings.language)
            Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
                AppLanguage.entries.forEach { option ->
                    val label = when (option) {
                        AppLanguage.SYSTEM -> strings.languageSystem
                        AppLanguage.CHINESE -> strings.languageChinese
                        AppLanguage.ENGLISH -> strings.languageEnglish
                    }
                    BasicComponent(
                        title = label,
                        summary = if (option == AppLanguage.SYSTEM) strings.languageSummary else null,
                        endActions = {
                            Checkbox(
                                state = ToggleableState(language == option),
                                onClick = {
                                    onLanguageChange(option)
                                    ConfigManager.setLanguage(option.id)
                                    ModuleLogs.append("Settings", "language=${option.id}")
                                },
                            )
                        },
                        onClick = {
                            onLanguageChange(option)
                            ConfigManager.setLanguage(option.id)
                            ModuleLogs.append("Settings", "language=${option.id}")
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
