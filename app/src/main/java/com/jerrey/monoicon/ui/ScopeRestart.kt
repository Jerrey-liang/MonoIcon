package com.jerrey.monoicon.ui

import android.content.Context
import android.content.Intent
import com.jerrey.monoicon.hook.HyperOs4Environment
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.safety.LauncherSafety
import java.util.concurrent.TimeUnit

private const val TAG = "MonoIcon.ScopeRestart"

/**
 * Restarts the selected launcher and SystemUI processes. Android restarts these persistent
 * apps with the current module configuration.
 *
 * `killall` is used instead of `am force-stop` because force-stop marks the package as
 * *stopped*, which would leave a scoped system app dead until it is launched again.
 *
 * @param packages system apps to restart.
 * @return true when the scope was restarted (or at least killed).
 */
fun restartScopedApps(context: Context, packages: List<String>): Boolean {
    val scope = packages.filter { it.isNotBlank() }.distinct()
    if (scope.isEmpty()) return false
    val allowed = scope.filter { it in listOf("com.miui.home", "com.android.systemui") }
    if (allowed.size != scope.size) return false
    val prepare = if ("com.miui.home" in scope && !HyperOs4Environment.isVersion4()) {
        LauncherSafety.expectedRestartScript(LauncherSafety.launcherUid(context) ?: return false)
    } else "true"
    val killAll = scope.joinToString("\n") {
        if (it == "com.miui.home") "killall $it || { [ -z \"${'$'}expected\" ] || rm -f \"${'$'}expected\"; exit 1; }"
        else "killall $it || exit 1"
    }
    val script = "$prepare\n[ ${'$'}? -eq 0 ] || exit 1\n$killAll"
    for (cmd in listOf(arrayOf("su", "-M", "-c", script), arrayOf("su", "-c", script))) {
        try {
            val process = ProcessBuilder(*cmd).redirectOutput(java.io.File("/dev/null"))
                .redirectError(java.io.File("/dev/null")).start()
            val success = try { process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 }
                finally { if (process.isAlive) process.destroyForcibly() }
            if (success) {
                logi(TAG, "restarted ${scope.joinToString()}")
                bringLauncherBack(context, scope)
                return true
            }
        } catch (t: Throwable) {
            loge(TAG, "restart attempt failed", t)
        }
    }
    logw(TAG, "killall unavailable for ${scope.joinToString()}")
    return false
}

/**
 * Killing the launcher can leave the home screen empty until something asks for HOME,
 * so nudge the system once the scope has been killed.
 */
private fun bringLauncherBack(context: Context, scope: List<String>): Boolean {
    if ("com.miui.home" !in scope) return true
    return try {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        logi(TAG, "HOME intent sent")
        true
    } catch (t: Throwable) {
        loge(TAG, "HOME intent failed", t)
        false
    }
}
