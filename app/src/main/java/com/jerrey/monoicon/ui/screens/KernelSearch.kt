// Search bar, fake bar and pager adapted from KernelSU SuperSearchBar.kt and SearchStatus.kt.
// GPL-3.0-or-later; revision 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
// See docs/THIRD_PARTY_NOTICES.md. Icons use the Miuix 0.9.4 weight-based API.
package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Stable
internal data class KernelSearchStatus(
    val searchText: String = "",
    val current: Status = Status.COLLAPSED,
    val offsetY: Dp = 0.dp,
) {
    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
    fun isExpanded() = current == Status.EXPANDED
    fun isCollapsed() = current == Status.COLLAPSED
    fun shouldExpand() = current == Status.EXPANDED || current == Status.EXPANDING
    fun shouldCollapse() = current == Status.COLLAPSED || current == Status.COLLAPSING
    fun collapse() = copy(searchText = "", current = Status.COLLAPSING)
    fun animationComplete() = when (current) {
        Status.EXPANDING -> copy(current = Status.EXPANDED)
        Status.COLLAPSING -> copy(searchText = "", current = Status.COLLAPSED)
        else -> this
    }

    companion object {
        val Saver = listSaver<KernelSearchStatus, Any>(
            save = { listOf(it.searchText, it.shouldExpand(), it.offsetY.value) },
            restore = { KernelSearchStatus(it[0] as String,
                if (it[1] as Boolean) Status.EXPANDED else Status.COLLAPSED, (it[2] as Float).dp) },
        )
    }
}

/** A non-editable pill opens the full-screen search, as in KernelSU's app list. */
@Composable
internal fun KernelSearchBarFake(label: String, topPadding: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .padding(top = topPadding, bottom = 6.dp).heightIn(min = 45.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchLeadingIcon()
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceContainerHigh, maxLines = 1,
            modifier = Modifier.weight(1f).padding(end = 16.dp))
    }
}

/** KernelSU's expanding search overlay keeps gestures inside search until cancelled. */
@Composable
internal fun KernelSearchPager(
    status: KernelSearchStatus,
    onStatusChange: (KernelSearchStatus) -> Unit,
    clearDescription: String,
    topPadding: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val latestStatus by rememberUpdatedState(status)
    val latestOnStatusChange by rememberUpdatedState(onStatusChange)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun cancelSearch() {
        keyboard?.hide()
        focusManager.clearFocus()
        latestOnStatusChange(latestStatus.collapse())
    }
    BackHandler(enabled = status.shouldExpand()) { cancelSearch() }
    val systemTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val animatedTop by animateDpAsState(
        targetValue = if (status.shouldExpand()) systemTop + 5.dp else max(status.offsetY, 0.dp),
        animationSpec = tween(300, easing = LinearOutSlowInEasing),
        label = "SearchPagerTopPadding",
        finishedListener = { latestOnStatusChange(latestStatus.animationComplete()) },
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = if (status.shouldExpand()) 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing), label = "SearchPagerSurfaceAlpha",
    )
    val surface = MiuixTheme.colorScheme.surface
    if (!status.isCollapsed()) Column(
        modifier = Modifier.fillMaxSize().zIndex(5f)
            .drawBehind { drawRect(surface.copy(alpha = surfaceAlpha)) }
            .pointerInput(Unit) { detectTapGestures { } }
            .draggable(rememberDraggableState { }, Orientation.Horizontal)
            .testTag("app-icon-search-overlay"),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = animatedTop).background(surface),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                KernelSearchBar(status, onStatusChange, clearDescription, topPadding)
            }
            AnimatedVisibility(
                visible = status.shouldExpand(),
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it }),
            ) {
                Text(stringResource(android.R.string.cancel), fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = topPadding, bottom = 6.dp)
                        .clickable(interactionSource = null, indication = null, enabled = status.isExpanded()) {
                            cancelSearch()
                        }.testTag("app-icon-search-cancel"))
            }
        }
        AnimatedVisibility(status.isExpanded(), modifier = Modifier.fillMaxSize(),
            enter = fadeIn(), exit = fadeOut()) { content() }
    }
}

@Composable
private fun KernelSearchBar(
    status: KernelSearchStatus,
    onStatusChange: (KernelSearchStatus) -> Unit,
    clearDescription: String,
    topPadding: Dp,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember { mutableStateOf(TextFieldValue(status.searchText)) }
    LaunchedEffect(status.searchText) {
        if (value.text != status.searchText) value = TextFieldValue(status.searchText)
    }
    BasicTextField(
        value = value,
        onValueChange = { value = it; onStatusChange(status.copy(searchText = it.text)) },
        singleLine = true,
        textStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp,
            color = MiuixTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .padding(top = topPadding, bottom = 6.dp).heightIn(min = 45.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .focusRequester(focusRequester).testTag("app-icon-search"),
        decorationBox = { innerTextField ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchLeadingIcon()
                Box(Modifier.weight(1f)) { innerTextField() }
                AnimatedVisibility(status.searchText.isNotEmpty(), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                    Icon(MiuixIcons.Regular.Close2, clearDescription, tint = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.size(44.dp).padding(start = 8.dp, end = 16.dp)
                            .clickable(interactionSource = null, indication = null) {
                                value = TextFieldValue("")
                                onStatusChange(status.copy(searchText = ""))
                            }.testTag("app-icon-search-clear"))
                }
            }
        },
    )
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun SearchLeadingIcon() {
    Icon(MiuixIcons.Regular.Search, null,
        modifier = Modifier.size(44.dp).padding(start = 16.dp, end = 8.dp),
        tint = MiuixTheme.colorScheme.onSurfaceContainerHigh)
}
