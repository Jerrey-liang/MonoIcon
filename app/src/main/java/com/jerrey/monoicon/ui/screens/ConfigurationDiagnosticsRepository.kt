package com.jerrey.monoicon.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HookDiagnostics
import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileMatch
import com.jerrey.monoicon.profile.ProfileNative
import com.jerrey.monoicon.profile.ProfileRepository
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class LauncherEngine { JAVA, NATIVE, UNKNOWN }
internal data class LauncherEnvironment(
    val engine: LauncherEngine = LauncherEngine.UNKNOWN,
    val system: String = "",
    val launcher: String = "",
    val uid: Int? = null,
    val sdk: Int = Build.VERSION.SDK_INT,
    val abi: String = Build.SUPPORTED_ABIS.joinToString(),
)
internal data class RuntimeHookPoint(
    val id: String,
    val group: String,
    val member: String,
    val status: String = "UNKNOWN",
    val detail: String = "",
    val hit: Boolean = false,
    val fields: List<Pair<String, String>> = emptyList(),
)
internal data class RuntimeHookProcess(
    val name: String,
    val pid: Int? = null,
    val current: Boolean = false,
    val capturedAtMillis: Long = 0,
    val detail: String = "",
    val points: List<RuntimeHookPoint> = emptyList(),
    val fields: List<Pair<String, String>> = emptyList(),
)
internal data class ConfigurationRuntime(
    val environment: LauncherEnvironment = LauncherEnvironment(),
    val enabled: Boolean = false,
    val safetyBlocked: Boolean = false,
    val processes: List<RuntimeHookProcess> = emptyList(),
)

/** Worker-only observation; neither querying nor refreshing changes icon configuration. */
internal object ConfigurationDiagnosticsRepository {
    private val packages = listOf("com.miui.home", "com.android.systemui")
    private val libcPoints = listOf("open", "openat", "__open_2", "__openat_2", "stat", "stat64",
        "lstat64", "fstatat64", "fstatat", "access", "faccessat", "fopen", "opendir", "madvise")

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
            system = runCatching { ProfileNative.systemProperty(ProfileMatch.SYSTEM_VERSION_PROPERTY) }.getOrDefault(""),
            launcher = info?.let { "${it.versionName.orEmpty()} (${it.longVersionCode})" }.orEmpty(),
            uid = app?.uid,
        )
    }

    suspend fun load(context: Context): ConfigurationRuntime {
        val app = context.applicationContext
        val environment = environment(app)
        val nonce = HookDiagnostics.requestSnapshots(app)
        val native = if (environment.engine == LauncherEngine.NATIVE && environment.uid != null) {
            readNative(app, environment.uid)
        } else null
        // Give the already-running Java hosts time to answer without doing IO in their hook callbacks.
        delay(650)
        val boot = HookDiagnostics.currentBootId()
        val reports = HookDiagnostics.readSnapshots(app)
        val activePackages = if (environment.engine == LauncherEngine.NATIVE) packages.drop(1) else packages
        val processes = activePackages.map { pkg ->
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
            RuntimeHookProcess(pkg + " · Java", report?.pid, current, report?.capturedAtMillis ?: 0,
                report?.let { "${it.processName} · ${it.moduleVersion}" }.orEmpty(), points)
        }.toMutableList()
        if (native != null) processes.add(0, native)
        return ConfigurationRuntime(environment, ConfigManager.isEnabledFromUi(), ConfigManager.isSafetyBlockedFromUi(), processes)
    }

    private fun expectedNativePoints(profile: CompatibilityProfile?): List<RuntimeHookPoint> {
        val points = (listOf("pthread_create") + libcPoints).map {
            RuntimeHookPoint(it, if (it == "pthread_create") "startup-hook" else "libc-hook", it)
        }.toMutableList()
        if (profile == null) return points
        runCatching {
            val root = JSONObject(profile.source.toString(Charsets.UTF_8))
            val images = root.getJSONArray("images").let { array -> (0 until array.length()).associate {
                val image = array.getJSONObject(it); image.getString("id") to image.getString("library")
            } }
            val functions = root.getJSONArray("functions").let { array -> (0 until array.length()).associate {
                val function = array.getJSONObject(it); function.getString("id") to function
            } }
            val patches = root.getJSONArray("patches")
            for (index in 0 until patches.length()) {
                val patch = patches.getJSONObject(index)
                val function = functions.getValue(patch.getString("function"))
                val offset = number(function.get("address")) + number(patch.get("offset"))
                points += RuntimeHookPoint(patch.getString("id"), "profile-patch", function.getString("id"),
                    fields = listOf("library" to images.getValue(function.getString("image")),
                        "profileRva" to "0x${offset.toString(16)}"))
            }
        }
        return points
    }

    private fun number(value: Any): Long = value.toString().let {
        if (it.startsWith("0x")) it.drop(2).toLong(16) else it.toLong()
    }

    private fun readNative(context: Context, uid: Int): RuntimeHookProcess {
        val resolution = runCatching { ProfileRepository.resolve(context) }.getOrNull()
        val profile = resolution?.profile ?: runCatching {
            val selected = ProfileRepository.selected(context)
            ProfileRepository.catalog(context).profiles.firstOrNull { it.id == selected }
        }.getOrNull()
        val expected = expectedNativePoints(profile)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val user = uid / 100_000
        val script = """
            for directory in /data/user_de/$user/com.miui.home/files /data/user/$user/com.miui.home/files; do
                [ -d "${'$'}directory" ] || continue
                request="${'$'}directory/monoicon_hyos_hooks.request"
                [ ! -L "${'$'}request" ] && [ ! -L "${'$'}request.new" ] || continue
                printf '%s' '$nonce' > "${'$'}request.new" && chown $uid:$uid "${'$'}request.new" &&
                    chmod 600 "${'$'}request.new" && restorecon "${'$'}request.new" && mv -f "${'$'}request.new" "${'$'}request"
            done
            sleep 0.7
            printf '@BOOT '
            cat /proc/sys/kernel/random/boot_id
            for pid in ${'$'}(pidof com.miui.home); do
                printf '@PROCESS '
                cat "/proc/${'$'}pid/stat" 2>/dev/null
            done
            for directory in /data/user_de/$user/com.miui.home/files /data/user/$user/com.miui.home/files; do
                report="${'$'}directory/monoicon_hyos_hooks.json"
                [ -f "${'$'}report" ] && [ ! -L "${'$'}report" ] || continue
                printf '\n@REPORT\n'
                head -c 1048576 "${'$'}report"
                printf '\n@END\n'
            done
        """.trimIndent()
        val output = File.createTempFile("hook-diagnostics-", ".txt", context.cacheDir)
        val text = try {
            val process = ProcessBuilder("su", "-M", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) throw IllegalStateException("Root diagnostics timed out")
                if (output.length() > 3 * 1024 * 1024) throw IllegalStateException("Diagnostic response is too large")
                output.readText()
            } finally { if (process.isAlive) process.destroyForcibly() }
        } catch (failure: Exception) {
            return RuntimeHookProcess("com.miui.home · Native", detail = failure.message.orEmpty(), points = expected)
        } finally { output.delete() }
        val boot = text.lineSequence().firstOrNull { it.startsWith("@BOOT ") }?.removePrefix("@BOOT ")?.trim().orEmpty()
        val processes = text.lineSequence().filter { it.startsWith("@PROCESS ") }.mapNotNull { line ->
            runCatching {
                val stat = line.removePrefix("@PROCESS ")
                stat.substringBefore(' ').toInt() to stat.substringAfterLast(") ").split(' ')[19].toLong()
            }.getOrNull()
        }.toMap()
        val candidates = text.split("@REPORT\n").drop(1).mapNotNull {
            runCatching { JSONObject(it.substringBefore("\n@END")) }.getOrNull()
        }.filter { it.optInt("schema") == 1 }
        val root = candidates.firstOrNull {
            currentNativeReport(it.optInt("pid"), it.optLong("startTicks"), it.optString("bootId"),
                boot, it.optString("requestNonce"), nonce, processes)
        } ?: candidates.firstOrNull { it.optString("requestNonce") == nonce } ?: candidates.firstOrNull()
            ?: return RuntimeHookProcess("com.miui.home · Native", points = expected,
                detail = text.take(800).trim())
        val current = currentNativeReport(root.optInt("pid"), root.optLong("startTicks"), root.optString("bootId"),
            boot, root.optString("requestNonce"), nonce, processes)
        val pointArray = root.optJSONArray("points")
        val points = (0 until minOf(pointArray?.length() ?: 0, 512)).map { index ->
            val point = pointArray!!.getJSONObject(index)
            RuntimeHookPoint(point.optString("id"), point.optString("kind"), point.optString("function"),
                point.optString("state", "UNKNOWN"), point.optString("detail"), fields = listOf(
                    "library", "offset", "target", "loadBias", "original", "replacement", "observed", "committed", "valueType", "aliasOf", "originPid", "resultCode",
                ).mapNotNull { field -> point.optString(field).takeIf(String::isNotBlank)?.let { field to it } })
        }.toMutableList()
        val known = points.mapTo(mutableSetOf()) { it.group to it.id }
        points += expected.filter { (it.group to it.id) !in known }
        val fields = mutableListOf("profileId" to root.optString("profileId"), "profileDigest" to root.optString("profileDigest"),
            "phase" to root.optString("phase"), "result" to root.optString("result"), "startTicks" to root.optString("startTicks"))
        listOf("reason", "installerStopped", "iconsEnabled", "requestedProfileDigest", "foregroundPage").forEach {
            root.optString(it).takeIf(String::isNotBlank)?.let { value -> fields += it to value }
        }
        root.optJSONObject("nativeInit")?.let { init ->
            listOf("apiVersion", "hookAvailable", "unhookAvailable", "spawner", "child", "sequence", "entryPoint", "originPid")
                .forEach { fields += it to init.optString(it) }
        }
        root.optJSONArray("images")?.let { images -> for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            listOf("loadBias", "path", "fileOffset", "inode").forEach { field ->
                fields += "${image.optString("library", image.optString("id"))} · $field" to image.optString(field)
            }
        } }
        val warning = if (resolution?.profile?.digest != null && resolution.profile.digest != root.optString("profileDigest"))
            "Profile digest differs from the current configuration" else ""
        return RuntimeHookProcess("com.miui.home · Native", root.optInt("pid"), current,
            root.optLong("capturedAtMillis"), warning, points, fields.filter { it.second.isNotBlank() })
    }
}
