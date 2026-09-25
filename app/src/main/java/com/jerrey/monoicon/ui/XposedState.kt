package com.jerrey.monoicon.ui

import android.content.Context
import android.content.pm.PackageInfo
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
        fun snapshot(service: XposedService? = ConfigManager.xposedService()): XposedState {
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
 * One application MonoIcon is scoped to, with the data the overview page shows.
 *
 * [versionName] comes from the installed package's [PackageInfo] — it is never a
 * constant in this module. `null` means the package is not installed (or the
 * query failed), which the UI renders as [VERSION_UNAVAILABLE].
 */
data class ScopeApp(
    val packageName: String,
    val versionName: String?,
) {
    /** `Version 16`, or [VERSION_UNAVAILABLE] when the package cannot be queried. */
    fun versionLabel(prefix: String): String =
        versionName?.takeIf { it.isNotBlank() }?.let { "$prefix $it" } ?: VERSION_UNAVAILABLE

    companion object {
        const val VERSION_UNAVAILABLE = "—"

        /**
         * Resolves the real version of every package in [packages] through the
         * PackageManager of [context]. Blocking — call from a background thread.
         */
        fun load(context: Context, packages: List<String>): List<ScopeApp> = packages.map { pkg ->
            ScopeApp(packageName = pkg, versionName = installedVersion(context, pkg))
        }

        /** `versionName` of the installed [pkg], or null when unavailable. */
        private fun installedVersion(context: Context, pkg: String): String? = try {
            val info: PackageInfo = context.packageManager.getPackageInfo(pkg, 0)
            // Prefer the human-readable version, falling back to the monotonically
            // increasing versionCode when an app ships no versionName.
            info.versionName?.takeIf { it.isNotBlank() }
                ?: info.longVersionCode.takeIf { it > 0 }?.toString()
        } catch (_: Throwable) {
            null
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
