package com.jerrey.monoicon.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local manager preferences; these must never be published to hooked processes. */
data class UiSettings(
    val themeMode: Int = 0,
    val keyColor: Int = 0,
    val colorStyle: String = "TonalSpot",
    val colorSpec: String = "Spec2025",
    val navigationBadge: Boolean = true,
    val predictiveBack: Boolean = true,
    val swipeDismiss: Boolean = true,
    val pagerGestureMode: Int = 1,
    val pageScale: Float = 1f,
) {
    // These manager features are always enabled, including for older installs.
    val checkUpdate: Boolean get() = true
    val monet: Boolean get() = true
    val enableBlur: Boolean get() = true
    val floatingBottomBar: Boolean get() = true
    val glassBottomBar: Boolean get() = true
}

object UiPreferences {
    private var preferences: SharedPreferences? = null
    private val mutableState = MutableStateFlow(UiSettings())
    val state: StateFlow<UiSettings> = mutableState.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("manager_ui", Context.MODE_PRIVATE)
        preferences = prefs
        prefs.edit()
            .remove("check_update")
            .remove("monet")
            .remove("blur")
            .remove("floating_bar")
            .remove("glass_bar")
            .apply()
        mutableState.value = UiSettings(
            themeMode = prefs.getInt("theme_mode", 0).coerceIn(0, 2),
            keyColor = prefs.getInt("key_color", 0),
            colorStyle = prefs.getString("color_style", "TonalSpot") ?: "TonalSpot",
            colorSpec = prefs.getString("color_spec", "Spec2025") ?: "Spec2025",
            navigationBadge = prefs.getBoolean("navigation_badge", true),
            predictiveBack = prefs.getBoolean("predictive_back", true),
            swipeDismiss = prefs.getBoolean("swipe_dismiss", true),
            pagerGestureMode = prefs.getInt("pager_gesture", 1).coerceIn(0, 2),
            pageScale = prefs.getFloat("page_scale", 1f).coerceIn(0.8f, 1.1f),
        )
    }

    @Synchronized
    fun update(transform: (UiSettings) -> UiSettings) {
        val value = transform(mutableState.value)
        preferences?.edit()?.apply {
            putInt("theme_mode", value.themeMode)
            putInt("key_color", value.keyColor)
            putString("color_style", value.colorStyle)
            putString("color_spec", value.colorSpec)
            putBoolean("navigation_badge", value.navigationBadge)
            putBoolean("predictive_back", value.predictiveBack)
            putBoolean("swipe_dismiss", value.swipeDismiss)
            putInt("pager_gesture", value.pagerGestureMode.coerceIn(0, 2))
            putFloat("page_scale", value.pageScale.coerceIn(0.8f, 1.1f))
        }?.apply()
        mutableState.value = value.copy(
            themeMode = value.themeMode.coerceIn(0, 2),
            pagerGestureMode = value.pagerGestureMode.coerceIn(0, 2),
            pageScale = value.pageScale.coerceIn(0.8f, 1.1f),
        )
    }
}

@Composable
fun rememberUiSettings(): UiSettings {
    UiPreferences.init(LocalContext.current)
    val settings by UiPreferences.state.collectAsState()
    return settings
}

@Composable
fun isManagerDarkTheme(): Boolean = when (rememberUiSettings().themeMode) {
    1 -> false
    2 -> true
    else -> isSystemInDarkTheme()
}
