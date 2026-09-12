package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.ModuleLogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Runtime log page (Phase 13, secondary screen).
 *
 * Shows the module's hook logs read from logcat through `su` (the hooks run in
 * the launcher/SystemUI processes, so an app cannot read them directly) and, as
 * a fallback, the events recorded by the settings process itself.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var systemLogs by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun reload() {
        loading = true
        systemLogs = withContext(Dispatchers.IO) { ModuleLogs.readSystemLogs() }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.logsRefresh,
                summary = if (loading) "…" else null,
                onClick = { scope.launch { reload() } },
            )
        }

        val lines = systemLogs
        if (lines == null) {
            Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = strings.logsRootHint,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = strings.logsInAppOnly,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            LogLines(ModuleLogs.inAppLogs(), strings.logsEmpty)
        } else {
            LogLines(lines, strings.logsEmpty)
        }
    }
}

@Composable
private fun LogLines(lines: List<String>, emptyText: String) {
    Card(modifier = Modifier.padding(horizontal = 16.dp)) {
        // No inner verticalScroll: this page already lives inside the host's
        // scrolling column, and a nested same-axis scrollable would be measured
        // with infinite height constraints (crash).
        Column(modifier = Modifier.padding(12.dp)) {
            if (lines.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = lines.joinToString("\n"),
                    style = MiuixTheme.textStyles.footnote2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(
                            color = MiuixTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                )
            }
        }
    }
}
