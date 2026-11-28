package com.jerrey.monoicon.theme.archive

import java.lang.reflect.InvocationTargetException

internal object ThemeIconRefresh {
    fun request(
        broadcast: () -> Unit,
        warn: (String, Throwable) -> Unit,
        resolve: (String) -> Class<*> = { Class.forName(it) },
    ): Boolean {
        fun attempt(operation: String, action: () -> Unit): Boolean =
            runCatching(action).onFailure { failure ->
                warn(operation, (failure as? InvocationTargetException)?.targetException ?: failure)
            }.isSuccess

        attempt("IconCustomizer.clearCustomizedIcons") {
            resolve("miui.content.res.IconCustomizer").getMethod("clearCustomizedIcons", String::class.java)
                .invoke(null, null as String?)
        }
        fun reset(type: String) = attempt("$type.getSystem().resetIcons") {
            val resources = checkNotNull(resolve("miui.content.res.$type").getMethod("getSystem").invoke(null))
            resources.javaClass.getMethod("resetIcons").invoke(resources)
        }
        if (!reset("ThemeResources")) reset("ThemeResourcesSystem")
        val notified = attempt("MiuiConfiguration.sendThemeConfigurationChangeMsg(8)") {
            resolve("android.content.res.MiuiConfiguration")
                .getMethod("sendThemeConfigurationChangeMsg", Long::class.javaPrimitiveType).invoke(null, 8L)
        }
        attempt("ACTION_THEME_CHANGED broadcast", broadcast)
        return notified
    }
}
