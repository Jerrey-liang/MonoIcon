package com.jerrey.monoicon.ui.settings

import android.os.FileObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.profile.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File

/** Counts public app updates and installed compatibility profiles, matching tab order. */
@Composable
fun rememberNavigationBadges(): Map<Int, Int> {
    val context = LocalContext.current.applicationContext
    val settings = rememberUiSettings()
    val update by AppUpdateChecker.state.collectAsState()
    val publication by HyperOs4PalettePublisher.status.collectAsState()
    val profileCount by produceState(0, context, publication) {
        withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "hyperos4-profiles").apply { mkdirs() }
            callbackFlow {
                val observer = object : FileObserver(
                    directory, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.DELETE,
                ) {
                    override fun onEvent(event: Int, path: String?) { trySend(Unit) }
                }
                observer.startWatching()
                trySend(Unit)
                awaitClose { observer.stopWatching() }
            }.conflate().collect {
                value = runCatching { ProfileRepository.catalog(context).profiles.size }.getOrDefault(0)
            }
        }
    }
    if (!settings.navigationBadge) return emptyMap()
    return buildMap {
        if (settings.checkUpdate && update.availableRelease != null) put(0, 1)
        if (profileCount > 0) put(2, profileCount)
    }
}
