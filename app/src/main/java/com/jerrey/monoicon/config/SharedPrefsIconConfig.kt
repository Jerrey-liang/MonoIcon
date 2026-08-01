package com.jerrey.monoicon.config

import android.content.Context
import android.content.SharedPreferences
import com.jerrey.monoicon.logging.logd
import com.jerrey.monoicon.logging.logw
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MonoIcon.Config"

private const val PREFS_NAME = "monoicon_config"
private const val KEY_ENABLED = "enabled"
private const val KEY_ICON_STYLE = "icon_style"
private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"

/**
 * [SharedPreferences]-backed implementation of [IconConfig].
 *
 * Uses [MutableStateFlow] to provide reactive [isEnabledFlow] updates
 * within the module's own process. For cross-process consistency,
 * a future version may migrate to DataStore or a ContentProvider.
 */
internal class SharedPrefsIconConfig(context: Context) : IconConfig {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isEnabledFlow = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    override val isEnabledFlow: Flow<Boolean> = _isEnabledFlow.asStateFlow()

    override val isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)

    override val iconStyle: IconStyle
        get() {
            val name = prefs.getString(KEY_ICON_STYLE, null)
            return if (name != null) {
                try {
                    IconStyle.valueOf(name)
                } catch (e: IllegalArgumentException) {
                    logw(TAG, "Unknown icon style in prefs: $name, falling back to ADAPTIVE")
                    IconStyle.MONOCHROME_ADAPTIVE
                }
            } else {
                IconStyle.MONOCHROME_ADAPTIVE
            }
        }

    override val excludedPackages: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()) ?: emptySet()

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _isEnabledFlow.value = enabled
        logd(TAG, "Enabled set to: $enabled")
    }

    override suspend fun setIconStyle(style: IconStyle) {
        prefs.edit().putString(KEY_ICON_STYLE, style.name).apply()
        logd(TAG, "IconStyle set to: ${style.name}")
    }

    override suspend fun addExcludedPackage(packageName: String) {
        val current = excludedPackages.toMutableSet()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, current).apply()
        logd(TAG, "Added excluded package: $packageName")
    }

    override suspend fun removeExcludedPackage(packageName: String) {
        val current = excludedPackages.toMutableSet()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_EXCLUDED_PACKAGES, current).apply()
        logd(TAG, "Removed excluded package: $packageName")
    }
}
