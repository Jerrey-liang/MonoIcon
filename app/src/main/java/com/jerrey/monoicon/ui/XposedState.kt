package com.jerrey.monoicon.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.jerrey.monoicon.config.ConfigManager
import io.github.libxposed.service.XposedService
import java.util.Locale

/**
 * LSPosed/framework status snapshot for the overview card (Phase 13).
 *
 * Reads the [XposedService] binder that the settings process receives from the
 * framework (bound through `XposedServiceHelper` in
 * [ConfigManager.init]). Everything degrades to "unavailable" when the module is
 * not activated, so the card can always render.
 */
data class XposedState(
    val bound: Boolean,
    val frameworkName: String?,
    val frameworkVersion: String?,
    val frameworkVersionCode: Long,
    val apiVersion: Int,
    val scope: List<String>,
) {
    val active: Boolean get() = bound

    fun hasScope(packageName: String): Boolean = scope.any { it == packageName }

    /**
     * Framework version as displayed by LSPosed itself, e.g. `2.1.1 (7790)`.
     * Falls back to whichever part the framework reports.
     */
    val frameworkLabel: String
        get() {
            val version = frameworkVersion?.takeIf { it.isNotBlank() } ?: return "—"
            return if (frameworkVersionCode > 0) "$version ($frameworkVersionCode)" else version
        }

    companion object {
        val UNAVAILABLE = XposedState(
            bound = false,
            frameworkName = null,
            frameworkVersion = null,
            frameworkVersionCode = 0L,
            apiVersion = 0,
            scope = emptyList(),
        )

        /** Packages MonoIcon needs to be scoped to. */
        val REQUIRED_SCOPE = listOf("com.miui.home", "com.android.systemui")

        /** Reads the current status from the bound framework service. */
        fun snapshot(): XposedState {
            val service: XposedService? = ConfigManager.xposedService()
            if (service == null) return UNAVAILABLE
            return try {
                XposedState(
                    bound = true,
                    frameworkName = service.frameworkName,
                    frameworkVersion = service.frameworkVersion,
                    frameworkVersionCode = service.frameworkVersionCode,
                    apiVersion = service.apiVersion,
                    scope = service.scope.toList(),
                )
            } catch (_: Throwable) {
                UNAVAILABLE
            }
        }
    }
}

/**
 * Asks the framework to add [packages] to the module scope (LSPosed shows a
 * confirmation). Best effort: returns false when the service is unavailable.
 */
fun requestScope(packages: List<String>): Boolean {
    val service = ConfigManager.xposedService() ?: return false
    return try {
        service.requestScope(
            packages,
            object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(scope: List<String>) {
                    ModuleLogs.append("Scope", "approved: ${scope.joinToString()}")
                }

                override fun onScopeRequestFailed(message: String) {
                    ModuleLogs.append("Scope", "request failed: $message")
                }
            },
        )
        ModuleLogs.append("Scope", "requested: ${packages.joinToString()}")
        true
    } catch (t: Throwable) {
        ModuleLogs.append("Scope", "request error: ${t.message}")
        false
    }
}

/** Wraps [base] with the locale of [languageId] (`system` keeps the device locale). */
fun wrapLocale(base: Context, languageId: String): Context {
    val language = AppLanguage.fromId(languageId)
    if (language == AppLanguage.SYSTEM) return base
    val locale = when (language) {
        AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.ENGLISH -> Locale.ENGLISH
        AppLanguage.SYSTEM -> return base
    }
    val configuration = Configuration(base.resources.configuration).apply {
        setLocales(LocaleList(locale))
    }
    return base.createConfigurationContext(configuration)
}

/** True when the device locale is Chinese (used to resolve [AppLanguage.SYSTEM]). */
fun systemPrefersChinese(context: Context): Boolean {
    val locales = context.resources.configuration.locales
    val tag = if (locales.isEmpty) Locale.getDefault().language else locales.get(0).language
    return tag.startsWith("zh")
}
