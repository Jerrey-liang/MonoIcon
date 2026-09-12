package com.jerrey.monoicon

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.MonoIconApp
import com.jerrey.monoicon.ui.wrapLocale

/**
 * Single-activity entry point for the MonoIcon configuration UI.
 *
 * This activity serves as the user-facing configuration screen
 * accessible from the app drawer. The actual icon processing
 * runs in the HyperOS Launcher / SystemUI processes via LSPosed hooks
 * ([com.jerrey.monoicon.hook.IconThemeHook]).
 *
 * When the module APK is loaded by LSPosed, the hook class
 * is instantiated in those processes independently of this activity.
 */
class MainActivity : ComponentActivity() {
    /**
     * Applies the UI language before the activity is created, so both our own
     * string tables and framework-provided strings (toasts, dialogs) match the
     * selection made in 模块设置 → 语言.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(wrapLocale(newBase, ConfigManager.languageFromPrefs(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phase 4.1: bind the module-process SharedPreferences so the UI
        // switch writes the same file the launcher hook process reads.
        ConfigManager.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            MonoIconApp()
        }
    }
}
