package com.jerrey.monoicon.ui

import android.content.Context
import android.content.Intent
import java.util.concurrent.TimeUnit

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

    val killAll = scope.joinToString("; ") { "killall $it" }
    val commands = listOf(
        "su -c '$killAll'",
        "su -c '$killAll'; $killAll",
        killAll,
    )
    for (cmd in commands) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (finished && process.exitValue() == 0) {
                ModuleLogs.append("ScopeRestart", "restarted ${scope.joinToString()} via: ${cmd.take(40)}…")
                bringLauncherBack(context, scope)
                return true
            }
        } catch (t: Throwable) {
            ModuleLogs.append("ScopeRestart", "attempt failed: ${t.message}")
        }
    }
    ModuleLogs.append("ScopeRestart", "killall unavailable for ${scope.joinToString()}")
    return bringLauncherBack(context, scope)
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
        ModuleLogs.append("ScopeRestart", "HOME intent sent")
        true
    } catch (t: Throwable) {
        ModuleLogs.append("ScopeRestart", "HOME intent failed: ${t.message}")
        false
    }
}
