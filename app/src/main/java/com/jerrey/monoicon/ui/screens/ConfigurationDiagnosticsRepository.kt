package com.jerrey.monoicon.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HookDiagnostics
import com.jerrey.monoicon.hook.HyperOs4Environment
import kotlinx.coroutines.delay

internal enum class LauncherEngine { JAVA, NATIVE, UNKNOWN }
internal data class LauncherEnvironment(
    val engine: LauncherEngine = LauncherEngine.UNKNOWN,
    val system: String = "",
    val launcher: String = "",
    val sdk: Int = Build.VERSION.SDK_INT,
    val abi: String = Build.SUPPORTED_ABIS.joinToString(),
    val isHyperOs4: Boolean = false,
    val themeArchiveAvailable: Boolean = false,
)
internal data class RuntimeHookPoint(
    val id: String,
    val group: String,
    val member: String,
    val status: String = "UNKNOWN",
    val detail: String = "",
    val hit: Boolean? = null,
    val fields: List<Pair<String, String>> = emptyList(),
)
internal data class RuntimeHookProcess(
    val name: String,
    val pid: Int? = null,
    val current: Boolean = false,
    val capturedAtMillis: Long = 0,
    val detail: String = "",
    val points: List<RuntimeHookPoint> = emptyList(),
)
internal data class ConfigurationRuntime(
    val environment: LauncherEnvironment = LauncherEnvironment(),
    val enabled: Boolean = false,
    val safetyBlocked: Boolean = false,
    val processes: List<RuntimeHookProcess> = emptyList(),
)

/** Worker-only observation; neither querying nor refreshing changes icon configuration. */
internal object ConfigurationDiagnosticsRepository {
    fun environment(context: Context): LauncherEnvironment {
        val info = runCatching {
            context.packageManager.getPackageInfo("com.miui.home", PackageManager.PackageInfoFlags.of(0))
        }.getOrNull()
        val app = info?.applicationInfo
        return LauncherEnvironment(
            engine = when {
                app == null -> LauncherEngine.UNKNOWN
                app.flags and ApplicationInfo.FLAG_HAS_CODE != 0 -> LauncherEngine.JAVA
                else -> LauncherEngine.NATIVE
            },
            system = Build.VERSION.INCREMENTAL,
            launcher = info?.let { "${it.versionName.orEmpty()} (${it.longVersionCode})" }.orEmpty(),
            isHyperOs4 = HyperOs4Environment.isVersion4(),
            themeArchiveAvailable = HyperOs4Environment.isSupported(context),
        )
    }

    suspend fun load(context: Context, environment: LauncherEnvironment): ConfigurationRuntime {
        val app = context.applicationContext
        val packages = if (environment.isHyperOs4 || environment.engine != LauncherEngine.JAVA) {
            listOf("com.android.systemui")
        } else listOf("com.miui.home", "com.android.systemui")
        val nonce = HookDiagnostics.requestSnapshots(app, packages)
        // Give the already-running Java hosts time to answer without doing IO in their hook callbacks.
        delay(650)
        val boot = HookDiagnostics.currentBootId()
        val reports = HookDiagnostics.readSnapshots(app)
        val processes = packages.map { pkg ->
            val report = reports.filter { it.packageName == pkg && it.processName == pkg }.maxByOrNull { it.capturedAtMillis }
            val current = report != null && report.requestNonce == nonce &&
                (boot == null || report.systemBootId == boot)
            val reported = report?.points.orEmpty()
            val known = reported.mapTo(mutableSetOf()) { it.name }
            val complete = reported + HookDiagnostics.expectedPoints(pkg).filter { it.name !in known }
            val points = complete.map { point ->
                RuntimeHookPoint(point.name, point.group, point.member, point.status.name, point.detail,
                    point.hitObserved, listOfNotNull(
                        "required" to point.required.toString(),
                        point.firstHitElapsedMs.takeIf { it > 0 }?.let { "firstHitElapsedMs" to it.toString() },
                    ))
            }
            RuntimeHookProcess("$pkg (Java)", report?.pid, current, report?.capturedAtMillis ?: 0,
                report?.moduleVersion.orEmpty(), points)
        }
        return ConfigurationRuntime(
            environment = environment,
            enabled = ConfigManager.isEnabledFromUi(),
            safetyBlocked = ConfigManager.isSafetyBlockedFromUi(),
            processes = processes,
        )
    }
}
