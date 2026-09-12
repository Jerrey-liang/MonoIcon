package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.XposedState
import com.jerrey.monoicon.ui.requestScope
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Overview tab (Phase 13).
 *
 * Layout follows the Liquid Glass hierarchy: a large left-aligned page title,
 * then the framework status on a **light accent container** (large "已激活"),
 * with the framework/API version as secondary text. Scope details and the
 * entries to the log/about pages sit on regular surfaces below — glass is
 * reserved for the navigation layer only.
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

    // Re-read on entry: the service may bind after the screen is composed.
    LaunchedEffect(Unit) {
        repeat(6) {
            status = XposedState.snapshot()
            if (status.bound) return@LaunchedEffect
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LargeScreenTitle(text = "MonoIcon")

        // ── Status hero card (light tone) ─────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MiuixTheme.colorScheme.primaryContainer,
            contentColor = MiuixTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = if (status.active) strings.active else strings.inactive,
                    style = MiuixTheme.textStyles.headline1.copy(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MiuixTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = status.frameworkLabel,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 16.sp),
                    color = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text(
                    text = "API ${status.apiVersion}",
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 16.sp),
                    color = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
            }
        }

        // ── Scope details ─────────────────────────────────────────────
        val missing = XposedState.REQUIRED_SCOPE.filterNot { status.hasScope(it) }
        Spacer(modifier = Modifier.height(8.dp))
        top.yukonga.miuix.kmp.basic.SmallTitle(text = strings.scope)
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            XposedState.REQUIRED_SCOPE.forEach { pkg ->
                val granted = status.hasScope(pkg)
                BasicComponent(
                    title = pkg,
                    summary = if (granted) strings.scopeGranted else strings.scopeMissing,
                    endActions = {
                        Text(
                            text = if (granted) "✓" else "✗",
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body1,
                            color = if (granted) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.error
                            },
                        )
                    },
                )
            }
            if (missing.isNotEmpty()) {
                BasicComponent(
                    title = if (scopeRequested) strings.scopeRequested else strings.requestScope,
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary,
                    ),
                    onClick = { scopeRequested = requestScope(missing) },
                )
            }
        }

        // ── Secondary pages ───────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.logs,
                summary = strings.logsTitle,
                onClick = onOpenLogs,
            )
            BasicComponent(
                title = strings.about,
                summary = "MonoIcon ${com.jerrey.monoicon.BuildConfig.VERSION_NAME}",
                onClick = onOpenAbout,
            )
        }
    }
}
