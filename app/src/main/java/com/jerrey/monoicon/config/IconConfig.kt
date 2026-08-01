package com.jerrey.monoicon.config

import kotlinx.coroutines.flow.Flow

/**
 * Central configuration contract for MonoIcon.
 *
 * Provides read/write access to all user-configurable settings.
 * Implementations are responsible for persistence (e.g., SharedPreferences).
 *
 * Both the hook process (HyperOS Launcher) and the UI process read from
 * the same configuration, so implementations must be thread-safe and
 * process-safe (use MODE_MULTI_PROCESS or a content-provider-backed store
 * if cross-process access is needed).
 */
interface IconConfig {

    // ── Read ──────────────────────────────────────────────────────

    /** Whether monochrome icon conversion is enabled. */
    val isEnabled: Boolean

    /** A [Flow] that emits [isEnabled] changes for reactive UI. */
    val isEnabledFlow: Flow<Boolean>

    /** The currently selected icon visual style. */
    val iconStyle: IconStyle

    /** Set of package names excluded from monochrome conversion. */
    val excludedPackages: Set<String>

    // ── Write ─────────────────────────────────────────────────────

    /** Enable or disable the monochrome icon conversion globally. */
    suspend fun setEnabled(enabled: Boolean)

    /** Set the icon visual style. */
    suspend fun setIconStyle(style: IconStyle)

    /** Add a package to the exclusion list (its icons will not be converted). */
    suspend fun addExcludedPackage(packageName: String)

    /** Remove a package from the exclusion list. */
    suspend fun removeExcludedPackage(packageName: String)
}
