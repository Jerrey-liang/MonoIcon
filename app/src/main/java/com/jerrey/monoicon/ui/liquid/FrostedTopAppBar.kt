package com.jerrey.monoicon.ui.liquid

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The backdrop source must be outside this bar's subtree. */
@Composable
fun FrostedTopAppBar(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
) {
    val surfaceColor = MiuixTheme.colorScheme.surface

    SmallTopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        // Keep Miuix's title styling, height and system/cutout insets. Only its
        // opaque background is suppressed; the same surface is drawn below.
        color = surfaceColor.copy(alpha = 0f),
        modifier = modifier
            .clipToBounds()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = { blur(16.dp.toPx(), 16.dp.toPx()) },
                // drawBackdrop composites this surface after the sampled blur
                // and before the bar's own text and navigation icon.
                onDrawSurface = { drawRect(color = surfaceColor, alpha = 0.72f) },
            ),
    )
}
