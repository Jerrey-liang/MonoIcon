// Adapted from KernelSU RebootListPopupMiuix, DropdownItem and MenuPositionProvider.
// GPL-3.0-or-later; revision 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.XposedState
import com.jerrey.monoicon.ui.restartScopedApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** KernelSU's power menu, applied to MonoIcon's actual hooked processes. */
@Composable
internal fun HomeTopBarActions(modifier: Modifier = Modifier) {
    val service by ConfigManager.serviceState.collectAsState()
    val strings = LocalStrings.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val enabled = service != null && !busy
    LaunchedEffect(service) {
        if (service == null) showMenu = false
    }
    val options = listOf(
        strings.scopeRestart to XposedState.REQUIRED_SCOPE,
        "${strings.restart} ${strings.scopeAppLauncher}" to listOf("com.miui.home"),
        "${strings.restart} ${strings.scopeAppSystemUi}" to listOf("com.android.systemui"),
    )
    IconButton(
        modifier = modifier,
        enabled = enabled,
        onClick = { showMenu = true },
        holdDownState = showMenu,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Close2,
            contentDescription = if (busy) strings.restarting else strings.scopeRestart,
            tint = MiuixTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
    OverlayListPopup(
        show = showMenu,
        popupPositionProvider = HomeMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { showMenu = false },
    ) {
        val dropdownColors = DropdownDefaults.dropdownColors()
        ListPopupColumn {
            options.forEachIndexed { index, (label, packages) ->
                Row(
                    modifier = Modifier
                        .clickable(enabled = enabled) {
                            showMenu = false
                            busy = true
                            coroutineScope.launch {
                                try {
                                    val success = withContext(Dispatchers.IO) {
                                        runCatching { restartScopedApps(context.applicationContext, packages) }.getOrDefault(false)
                                    }
                                    Toast.makeText(context, if (success) strings.restartDone else strings.restartFailed, Toast.LENGTH_SHORT).show()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                        .background(dropdownColors.containerColor)
                        .padding(horizontal = 20.dp)
                        .padding(top = if (index == 0) 20.dp else 12.dp, bottom = if (index == options.lastIndex) 20.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        fontSize = MiuixTheme.textStyles.body1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = dropdownColors.contentColor,
                    )
                }
            }
        }
    }
}

private val HomeMenuPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: PopupPositionProvider.Align,
    ): IntOffset {
        val resolved = alignment.resolveHomeDirection(layoutDirection)
        val offsetX: Int
        val offsetY: Int
        when (resolved) {
            PopupPositionProvider.Align.TopStart -> {
                offsetX = anchorBounds.left + popupMargin.left
                offsetY = anchorBounds.bottom + popupMargin.top
            }
            PopupPositionProvider.Align.TopEnd -> {
                offsetX = anchorBounds.right - popupContentSize.width - popupMargin.right
                offsetY = anchorBounds.bottom + popupMargin.top
            }
            PopupPositionProvider.Align.BottomStart -> {
                offsetX = anchorBounds.left + popupMargin.left
                offsetY = anchorBounds.top - popupContentSize.height - popupMargin.bottom
            }
            PopupPositionProvider.Align.BottomEnd -> {
                offsetX = anchorBounds.right - popupContentSize.width - popupMargin.right
                offsetY = anchorBounds.top - popupContentSize.height - popupMargin.bottom
            }
            else -> {
                offsetX = if (resolved == PopupPositionProvider.Align.End) {
                    anchorBounds.right - popupContentSize.width - popupMargin.right
                } else anchorBounds.left + popupMargin.left
                offsetY = when {
                    windowBounds.bottom - anchorBounds.bottom > popupContentSize.height -> anchorBounds.bottom + popupMargin.bottom
                    anchorBounds.top - windowBounds.top > popupContentSize.height -> anchorBounds.top - popupContentSize.height - popupMargin.top
                    else -> anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
                }
            }
        }
        return IntOffset(
            x = offsetX.coerceIn(windowBounds.left, (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(windowBounds.left)),
            y = offsetY.coerceIn(
                (windowBounds.top + popupMargin.top).coerceAtMost(windowBounds.bottom - popupContentSize.height - popupMargin.bottom),
                windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
            ),
        )
    }

    override fun getMargins(): PaddingValues = PaddingValues(start = 20.dp)
}

private fun PopupPositionProvider.Align.resolveHomeDirection(direction: LayoutDirection): PopupPositionProvider.Align {
    if (direction == LayoutDirection.Ltr) return this
    return when (this) {
        PopupPositionProvider.Align.Start -> PopupPositionProvider.Align.End
        PopupPositionProvider.Align.End -> PopupPositionProvider.Align.Start
        PopupPositionProvider.Align.TopStart -> PopupPositionProvider.Align.TopEnd
        PopupPositionProvider.Align.TopEnd -> PopupPositionProvider.Align.TopStart
        PopupPositionProvider.Align.BottomStart -> PopupPositionProvider.Align.BottomEnd
        PopupPositionProvider.Align.BottomEnd -> PopupPositionProvider.Align.BottomStart
    }
}
