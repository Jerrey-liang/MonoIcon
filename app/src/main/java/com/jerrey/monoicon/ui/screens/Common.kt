package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Large left-aligned page title (Apple HIG Liquid Glass: 34pt bold large title,
 * left aligned, content scrolls underneath the navigation layer).
 */
@Composable
fun LargeScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.headline1.copy(
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = MiuixTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp),
    )
}
