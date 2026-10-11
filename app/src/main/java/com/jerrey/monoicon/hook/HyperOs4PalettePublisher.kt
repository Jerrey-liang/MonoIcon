package com.jerrey.monoicon.hook

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.color.dynamic.PixelMonetColorEngine
import java.util.concurrent.TimeUnit

/**
 * Publishes the app-computed Material palette into the HyperOS 4 launcher.
 *
 * The HyperOS 4 launcher (com.miui.home) is a dex-less native process: LSPosed's
 * Java callbacks never run there, so this object cannot be executed inside it.
 * The native_init payload (hyperos4_palette_install.cpp) therefore reads the two
 * packed ARGB values from the launcher's own files directory:
 *
 *   /data/user/0/com.miui.home/files/monoicon_palette.txt
 *       "<background ARGB> <foreground ARGB>"
 *
 * This object runs in the MonoIcon app process and keeps that file current:
 *
 * 1. `publishPaletteFile` writes the file through the existing optional-root
 *    helper (su). Without root the file cannot be written and the launcher
 *    keeps its native look; the settings write below still delivers the
 *    background colour for users who grant "modify system settings".
 * 2. `notifyLauncher` posts a ContentObserver notification on the launcher's
 *    own settings URI (content://settings/system/key_monochrome_color). No
 *    permission is required to notify; the payload's equality-skip patches make
 *    every notification repaint, so this doubles as the refresh trigger.
 * 3. `writeLauncherSetting` writes the real Settings.System value when the user
 *    granted the WRITE_SETTINGS app-op, which also fires the launcher's own
 *    observer and lets a payload-less launcher apply the background itself.
 *
 * All steps are best-effort and never throw: a failure only means the launcher
 * keeps its previous palette.
 */
internal object HyperOs4PalettePublisher {
    private const val TAG = "MonoIcon.HyperOs4Palette"
    private const val PALETTE_PATH = "/data/user/0/com.miui.home/files/monoicon_palette.txt"
    private const val PALETTE_FALLBACK_PATH = "/data/local/tmp/monoicon_palette.txt"
    private const val SETTINGS_KEY = "key_monochrome_color"
    private var observedPrefs: SharedPreferences? = null

    @Volatile
    private var appContext: Context? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "variant_id") {
            val app = appContext ?: return@OnSharedPreferenceChangeListener
            publish(app)
        }
    }

    /** Publishes the current palette on a background thread. Never blocks the caller. */
    fun publish(context: Context) {
        val app = context.applicationContext ?: return
        appContext = app
        Thread({
            try {
                if (!ConfigManager.isEnabledFromUi()) return@Thread
                val palette = PixelMonetColorEngine.getIconColors()
                publishPaletteFile(palette.background, palette.foreground)
                writeLauncherSetting(app, palette.background)
                notifyLauncher(app)
                observeVariantChanges(app)
            } catch (t: Throwable) {
                Log.w(TAG, "palette publish failed: ${t.message}")
            }
        }, "MonoIcon-Hos4Palette").apply {
            isDaemon = true
            start()
        }
    }

    private fun observeVariantChanges(app: Context) {
        val prefs = try {
            app.getSharedPreferences("monoicon_config", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        } ?: return
        if (observedPrefs === prefs) return
        runCatching { observedPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        runCatching { prefs.registerOnSharedPreferenceChangeListener(prefsListener) }
        observedPrefs = prefs
    }

    private fun publishPaletteFile(background: Int, foreground: Int) {
        val content = String.format("0x%08x 0x%08x", background, foreground)
        val quoted = content.replace("\n", " ").trim()
        val targets = listOf(PALETTE_PATH, PALETTE_FALLBACK_PATH)
        for (target in targets) {
            // Shell-safe: the body is [0-9a-fx ] only.
            val commands = listOf(
                "su -c 'echo $quoted > $target'",
                "su -c \"echo $quoted > $target\"",
                "echo $quoted > $target",
            )
            for (cmd in commands) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    val finished = process.waitFor(5, TimeUnit.SECONDS)
                    if (finished && process.exitValue() == 0) {
                        Log.i(TAG, "palette file written: $target")
                        return
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "palette file write attempt failed: ${t.message}")
                }
            }
        }
        Log.w(TAG, "palette file write unavailable (no root helper)")
    }

    /** notifyChange needs no permission and reaches the launcher's registered observer. */
    private fun notifyLauncher(app: Context) {
        try {
            val uri = Settings.System.getUriFor(SETTINGS_KEY)
            app.contentResolver.notifyChange(uri, null, 0)
            Log.i(TAG, "launcher settings notification sent")
        } catch (t: Throwable) {
            Log.w(TAG, "launcher settings notification failed: ${t.message}")
        }
    }

    private fun writeLauncherSetting(app: Context, background: Int) {
        try {
            Settings.System.putString(
                app.contentResolver,
                SETTINGS_KEY,
                String.format("0x%08x", background),
            )
            Log.i(TAG, "launcher setting updated")
        } catch (t: Throwable) {
            Log.w(TAG, "launcher setting write unavailable: ${t.message}")
        }
    }
}
