package com.jerrey.monoicon.ui

import android.content.Context
import android.content.Intent
import java.util.concurrent.TimeUnit

/**
 * Restarts the HyperOS launcher (Phase 6.5, shared by the settings screens).
 *
 * Primary path: `su` + `am force-stop` + `am start HOME` — deterministic on
 * rooted devices; falls back to asking the system to start HOME.
 *
 * @return true when the launcher was restarted (or at least killed).
 */
fun restartHyperOSLauncher(context: Context): Boolean {
    val pkg = "com.miui.home"
    val commands = listOf(
        "am force-stop $pkg; " +
            "am start -a android.intent.action.MAIN -c android.intent.category.HOME",
        "su -c 'am force-stop $pkg'; su -c 'am start -a android.intent.action.MAIN -c android.intent.category.HOME'",
    )
    for (cmd in commands) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                ModuleLogs.append("LauncherRestart", "restarted via: ${cmd.take(40)}…")
                return true
            }
        } catch (t: Throwable) {
            ModuleLogs.append("LauncherRestart", "attempt failed: ${t.message}")
        }
    }
    return try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ModuleLogs.append("LauncherRestart", "HOME intent sent")
        true
    } catch (t: Throwable) {
        ModuleLogs.append("LauncherRestart", "HOME intent failed: ${t.message}")
        false
    }
}
