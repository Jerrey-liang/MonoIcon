package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.requestScope
import com.jerrey.monoicon.ui.XposedState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.MiuixIcons
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

        // ── Status hero card (LSPosed-style, light tone) ──────────────
        // Geometry measured off LSPosed's activation card (1080x2400 @2.625):
        // 12dp side margins, 110dp tall, "已激活" 22sp, inset 16dp/14dp.
        val heroShape = RoundedCornerShape(24.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = heroShape,
            color = MiuixTheme.colorScheme.primaryContainer,
            contentColor = Color.Black,
        ) {
            Box(modifier = Modifier.clip(heroShape)) {
                // Oversized circle-check watermark bleeding off the right edge,
                // like LSPosed's activation card.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(110.dp)
                        .offset(x = 34.dp)
                        .border(
                            width = 6.dp,
                            color = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f),
                            shape = CircleShape,
                        )
                        .padding(26.dp),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Ok,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.22f),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 14.dp,
                        end = 16.dp,
                        bottom = 4.dp,
                    ),
                ) {
                    Text(
                        text = if (status.active) strings.active else strings.inactive,
                        style = MiuixTheme.textStyles.headline1.copy(
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.Black,
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = status.frameworkLabel,
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.Black,
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = "API ${status.apiVersion}",
                        style = MiuixTheme.textStyles.body2.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.Black,
                    )
                }
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

        // ── Secondary pages (LSPosed-style: one card each + chevron) ──
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.logs,
                endActions = { TrailingChevron() },
                onClick = onOpenLogs,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Card(modifier = Modifier.padding(horizontal = 16.dp)) {
            BasicComponent(
                title = strings.about,
                endActions = { TrailingChevron() },
                onClick = onOpenAbout,
            )
        }
    }
}

/** Right-hand "›" affordance used by the secondary-page entries. */
@Composable
private fun TrailingChevron() {
    Icon(
        imageVector = MiuixIcons.Regular.ChevronForward,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
