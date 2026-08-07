package com.jerrey.monoicon

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.ui.MonoIconApp

/**
 * Single-activity entry point for the MonoIcon configuration UI.
 *
 * This activity serves as the user-facing configuration screen
 * accessible from the app drawer. The actual icon processing
 * runs in the HyperOS Launcher process via LSPosed hooks
 * ([com.jerrey.monoicon.hook.IconThemeHook]).
 *
 * When the module APK is loaded by LSPosed, the hook class
 * is instantiated in the launcher process independently of
 * this activity.
 */
class MainActivity : ComponentActivity() {
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
