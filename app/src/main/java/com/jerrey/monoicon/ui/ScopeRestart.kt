package com.jerrey.monoicon.ui

import android.content.Context
import android.content.Intent
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.hook.HyperOs4IconRefresh
import com.jerrey.monoicon.logging.loge
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.safety.LauncherSafety
import java.util.concurrent.TimeUnit

private const val TAG = "MonoIcon.ScopeRestart"

/**
 * Restarts every app in the module's Xposed scope (Phase 6.5, used by the icon-style
 * page's "重启作用域" action): the HyperOS launcher and SystemUI are persistent
 * processes, so killing them makes the framework bring them straight back — with the
 * hooks reloaded and the new configuration applied.
 *
 * `killall` is used instead of `am force-stop` because force-stop marks the package as
 * *stopped*, which would leave a scoped system app dead until it is launched again.
 *
 * @param packages scope packages to restart (normally [XposedState.REQUIRED_SCOPE]).
 * @return true when the scope was restarted (or at least killed).
 */
fun restartScopedApps(context: Context, packages: List<String>): Boolean {
    val scope = packages.filter { it.isNotBlank() }.distinct()
    if (scope.isEmpty()) return false
    if ("com.miui.home" in scope && !HyperOs4PalettePublisher.flushBeforeRestart(context)) return false

    val allowed = scope.filter { it in listOf("com.miui.home", "com.android.systemui") }
    if (allowed.size != scope.size) return false
    val prepare = if ("com.miui.home" in scope) {
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
        HyperOs4IconRefresh.afterLauncherRestart(context)
        true
    } catch (t: Throwable) {
        loge(TAG, "HOME intent failed", t)
        false
    }
}
