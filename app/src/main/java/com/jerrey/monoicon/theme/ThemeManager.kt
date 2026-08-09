package com.jerrey.monoicon.theme

import android.content.Context
import android.os.SystemClock
import com.jerrey.monoicon.config.ConfigManager
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * Central theme registry and lifecycle (Phase 5).
 *
 * Owns the current [IconTheme] selection and configures mask strategies
 * on theme switch. The hook process and the UI process both access
 * [currentTheme] — [initForHooks] reads the persisted theme ID from
 * remote preferences; [init] is available for UI-side preloading
 * (delegates to [ConfigManager]).
 *
 * ## Strategy caching
 * On [initForHooks] (and [setTheme]), each strategy's [theme mask strategy
 * configureCache] is called with the theme ID and context label so that
 * cache keys are namespaced per-theme-per-context.
 */
object ThemeManager {

    private const val TAG = "MonoIcon.Theme"
    private const val DEFAULT_THEME_ID = "pixel_default"

    // ── Current theme ──────────────────────────────────────────────────

    private val currentThemeRef = AtomicReference<IconTheme>(
        BuiltinThemes.themeFor(BuiltinThemes.PIXEL_DEFAULT))

    /** The currently active theme (always non-null). */
    val currentTheme: IconTheme get() = currentThemeRef.get()

    /** All built-in theme definitions for the settings UI. */
    val availableThemes: List<ThemeDefinition> = BuiltinThemes.ALL

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Launcher-process initialization: reads the persisted theme ID from
     * remote preferences and activates the corresponding theme. Call
     * from [IconThemeHook.onPackageLoaded] before installing hooks.
     */
    fun initForHooks(api: XposedInterface) {
        val themeId = try {
            ConfigManager.getThemeId()
        } catch (t: Throwable) {
            DEFAULT_THEME_ID
        }
        val definition = BuiltinThemes.byId(themeId)
        applyTheme(definition)
        android.util.Log.i(TAG, "initForHooks: theme=${definition.id}")
    }

    /**
     * Switches to the theme identified by [themeId]. Writes the new
     * ID to remote preferences via [ConfigManager]. Call from the
     * settings UI (module process).
     */
    fun setTheme(themeId: String) {
        ConfigManager.setThemeId(themeId)
        android.util.Log.i(TAG, "setTheme: $themeId (persisted)")
    }

    /** UI-side: reads the current theme ID from remote preferences. */
    fun currentThemeId(): String = try {
        ConfigManager.getThemeIdFromUi()
    } catch (t: Throwable) {
        DEFAULT_THEME_ID
    }

    // ── Internal ───────────────────────────────────────────────────────

    /**
     * Activates a [ThemeDefinition], configures its strategies, and
     * replaces the current theme reference.
     */
    private fun applyTheme(definition: ThemeDefinition) {
        definition.desktopMask.configureCache(definition.id, "desktop")
        definition.folderMask.configureCache(definition.id, "folder")
        currentThemeRef.set(BuiltinThemes.themeFor(definition))
    }
}
