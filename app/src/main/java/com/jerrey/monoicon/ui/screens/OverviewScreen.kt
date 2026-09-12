package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.icons.useful.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.XposedState
import com.jerrey.monoicon.ui.requestScope

/**
 * Overview tab (Phase 13).
 *
 * The LSPosed status sits in an accent-coloured card ([MiuixTheme.colorScheme]
 * `primary`), followed by the two entries that lead to the secondary pages:
 * runtime logs and about.
 */
@Composable
fun OverviewScreen(
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var status by remember { mutableStateOf(XposedState.snapshot()) }
    var scopeRequested by remember { mutableStateOf(false) }

    // Re-read on every entry: the service may bind after the screen appears.
    LaunchedEffect(Unit) {
        repeat(6) {
            status = XposedState.snapshot()
            if (status.bound) return@LaunchedEffect
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SmallTitle(text = strings.lsposedStatus)

        // ── Accent-coloured status card ───────────────────────────────
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MiuixTheme.colorScheme.primary,
            contentColor = MiuixTheme.colorScheme.onPrimary,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (status.active) strings.active else strings.inactive,
                        style = MiuixTheme.textStyles.headline2,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${strings.framework}: " +
                        (status.frameworkName ?: "LSPosed") +
                        (status.frameworkVersion?.let { " $it" } ?: ""),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
                if (status.apiVersion > 0) {
                    Text(
                        text = "${strings.apiVersion}: ${status.apiVersion}",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = strings.scope,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
                XposedState.REQUIRED_SCOPE.forEach { pkg ->
                    val granted = status.hasScope(pkg)
                    Text(
                        text = (if (granted) "✓ " else "✗ ") + pkg + "  " +
                            (if (granted) strings.scopeGranted else strings.scopeMissing),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }

                val missing = XposedState.REQUIRED_SCOPE.filterNot { status.hasScope(it) }
                if (missing.isNotEmpty() && !scopeRequested) {
                    Spacer(modifier = Modifier.height(10.dp))
                    BasicComponent(
                        title = strings.requestScope,
                        titleColor = top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(
                            color = MiuixTheme.colorScheme.onPrimary,
                        ),
                        rightActions = {
                            Icon(
                                imageVector = MiuixIcons.Useful.Info,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onPrimary,
                            )
                        },
                        onClick = {
                            scopeRequested = requestScope(missing)
                        },
                    )
                } else if (scopeRequested) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = strings.scopeRequested,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // ── Secondary page entries ────────────────────────────────────
        Spacer(modifier = Modifier.height(4.dp))
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.logs,
                summary = strings.logsTitle,
                onClick = onOpenLogs,
            )
            BasicComponent(
                title = strings.about,
                summary = "MonoIcon",
                onClick = onOpenAbout,
            )
        }
    }
}
