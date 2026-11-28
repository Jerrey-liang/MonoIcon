package com.jerrey.monoicon.hook

import android.os.FileObserver
import android.os.Handler
import com.jerrey.monoicon.theme.archive.LargeIconResources
import java.io.File

/** Start and stop on the handler thread, where all watch changes and notifications run. */
internal class ThemeDirectorySubscription(
    private val directory: File,
    private val handler: Handler,
    private val onIcons: () -> Unit,
    private val onLarge: () -> Unit,
) {
    private var parentObserver: FileObserver? = null
    private var themeObserver: FileObserver? = null
    private val largeObservers = mutableListOf<FileObserver>()

    fun start() {
        if (parentObserver != null) return
        parentObserver = watch(checkNotNull(directory.parentFile), MOVES) { path ->
            if (path == directory.name) {
                watchTheme()
                watchLarge()
                onIcons()
                onLarge()
            }
        }
        watchTheme()
        watchLarge()
    }

    fun stop() {
        parentObserver?.stopWatching()
        parentObserver = null
        themeObserver?.stopWatching()
        themeObserver = null
        clearLarge()
    }

    private fun watchTheme() {
        themeObserver?.stopWatching()
        // One watch per directory. Android can reuse the watch descriptor for the same directory.
        themeObserver = directory.takeIf(File::isDirectory)?.let { parent ->
            watch(parent, MOVES_AND_WRITES) { path ->
                when (path) {
                    "icons" -> onIcons()
                    "large_icons" -> { watchLarge(); onLarge() }
                }
            }
        }
    }

    private fun watchLarge() {
        clearLarge()
        // Watch the intermediate directories so first use and complete theme swaps are visible.
        var parent = File(directory, "large_icons")
        for (child in listOf("0", "apps", "desktop", LargeIconResources.CONFIG)) {
            if (!parent.isDirectory) break
            largeObservers += watch(parent, MOVES_AND_WRITES) { path ->
                if (path == child) {
                    if (child != LargeIconResources.CONFIG) watchLarge()
                    onLarge()
                }
            }
            parent = File(parent, child)
        }
    }

    private fun clearLarge() {
        largeObservers.forEach(FileObserver::stopWatching)
        largeObservers.clear()
    }

    private fun watch(parent: File, mask: Int, onChange: (String?) -> Unit): FileObserver =
        object : FileObserver(parent, mask) {
            override fun onEvent(event: Int, path: String?) {
                handler.post { if (parentObserver != null) onChange(path) }
            }
        }.also(FileObserver::startWatching)

    private companion object {
        const val MOVES = FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE
        const val MOVES_AND_WRITES = MOVES or FileObserver.CLOSE_WRITE
    }
}
