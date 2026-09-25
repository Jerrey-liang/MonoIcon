package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.hook.IconThemeHook
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.XposedState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * About page (Phase 13, secondary screen): what the module does, which packages
 * it hooks and which version is installed.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = strings.aboutBody,
                    style = MiuixTheme.textStyles.body2,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "${strings.aboutScope}: ${XposedState.REQUIRED_SCOPE.joinToString(" · ")}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${strings.aboutVersion}: ${com.jerrey.monoicon.BuildConfig.VERSION_NAME}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Material Dynamic Color 2025 · Lawnicons · AOSP mask pipeline",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
