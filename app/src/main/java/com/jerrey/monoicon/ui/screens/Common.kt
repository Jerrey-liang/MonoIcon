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
 * Large left-aligned page title, measured off LSPosed Manager's overview page
 * (1080x2400 @2.625): 32sp bold, left inset 28dp, cap top ~103dp below the screen
 * top, ~27dp of air before the first card.
 */
@Composable
fun LargeScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.headline1.copy(
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = MiuixTheme.colorScheme.onBackground,
        modifier = modifier.padding(start = 26.dp, top = 47.dp, bottom = 12.dp),
    )
}
