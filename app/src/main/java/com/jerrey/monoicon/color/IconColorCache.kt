package com.jerrey.monoicon.color

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache mapping component names to extracted icon colors.
 *
 * Populated at icon load time (IconProvider hook) and consumed at icon display time
 * (ShortcutIcon.setIconDrawable hook). No disk persistence — launcher restart clears
 * the cache, which is acceptable since colors are re-extracted on next load.
 *
 * ## Key format
 * `ComponentName.flattenToString()` — e.g. `com.google.android.gm/.ConversationListActivity`
 */
object IconColorCache {

    private val store = ConcurrentHashMap<String, Int>()

    /** Number of cached entries. */
    val size: Int get() = store.size

    /**
     * Stores the extracted color for [component].
     * Replaces any existing entry for the same component.
     */
    fun put(component: String, color: Int) {
        store[component] = color
    }

    /**
     * Returns the cached color for [component], or `null` if not cached.
     */
    fun get(component: String): Int? = store[component]

    /** Removes all entries. */
    fun clear() {
        store.clear()
    }
}
