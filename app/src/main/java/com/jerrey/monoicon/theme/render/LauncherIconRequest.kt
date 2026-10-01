package com.jerrey.monoicon.theme.render

import android.os.UserHandle

/**
 * Identity of one launcher icon-generation request.
 *
 * Everything that can change the rendered mask is part of the key, so a
 * request that repeats during a drawer scroll can reuse the previous result
 * instead of re-running the mask pipeline:
 *
 * - [identity]   `"pkg/cls"` — selects the raw APK drawable and the mask.
 * - [user]       work-profile icons of the same component render separately.
 * - [densityDpi] the launcher requests a different bitmap size per density.
 * - [configHash] `Configuration.hashCode()` — display size / ui-mode changes
 *                resize the mask the same way a density change does.
 *
 * Value equality ([UserHandle] compares its identifier) makes this usable as a
 * [com.jerrey.monoicon.cache.BoundedSingleFlightCache] key.
 */
data class LauncherIconRequest(
    val identity: String,
    val user: UserHandle,
    val densityDpi: Int,
    val configHash: Int,
)
