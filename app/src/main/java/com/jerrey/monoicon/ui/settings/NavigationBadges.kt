package com.jerrey.monoicon.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** The home tab shows a badge when a public app update is available. */
@Composable
fun rememberNavigationBadges(): Map<Int, Int> {
    val settings = rememberUiSettings()
    val update by AppUpdateChecker.state.collectAsState()
    return if (settings.navigationBadge && update.availableRelease != null) mapOf(0 to 1) else emptyMap()
}
