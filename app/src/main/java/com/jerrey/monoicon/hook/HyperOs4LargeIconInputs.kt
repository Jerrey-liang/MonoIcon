package com.jerrey.monoicon.hook

import android.content.Context
import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileIconRules
import com.jerrey.monoicon.profile.ProfileRepository
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Read-only snapshot of the launcher's MAML resource ownership and geometry. */
internal data class HyperOs4LargeIconInputs(
    val geometry: HyperOs4LargeIconGeometry.Geometry?,
    val icons: List<Icon>,
) {
    data class Icon(val path: String, val packageName: String)
    val signature: String = ProfileRepository.sha256((geometry?.signature.orEmpty() + "\n" +
        icons.joinToString("\n") { "${it.path}\t${it.packageName}" }).toByteArray())

    companion object {
        val EMPTY = HyperOs4LargeIconInputs(null, emptyList())
        private const val MAX_CONFIG_BYTES = 512 * 1024

        fun read(context: Context, profile: CompatibilityProfile?, launcherUid: Int): HyperOs4LargeIconInputs {
            val prefixes = profile?.icons?.largeIconPrefixes.orEmpty()
            if (prefixes.isEmpty()) return EMPTY
            val geometry = HyperOs4LargeIconGeometry.read(context, checkNotNull(profile), launcherUid) ?: return EMPTY
            val output = File.createTempFile("hyperos4-large-configs-", ".json", context.cacheDir)
            val globs = prefixes.joinToString(" ") { quote("$it${launcherUid / 100000}/apps/") + "*/runtime_largeicon.config" }
            val script = """
                count=0
                total=0
                printf '['
                for config in $globs; do
                    [ -f "${'$'}config" ] && [ ! -L "${'$'}config" ] || continue
                    bytes=${'$'}(stat -c %s "${'$'}config") || continue
                    case "${'$'}bytes" in ''|*[!0-9]*) continue ;; esac
                    [ "${'$'}bytes" -gt 0 ] && [ "${'$'}bytes" -le 131072 ] || continue
                    total=${'$'}((total + bytes))
                    [ "${'$'}total" -le $MAX_CONFIG_BYTES ] && [ "${'$'}count" -lt 64 ] || break
                    [ "${'$'}count" -eq 0 ] || printf ','
                    cat "${'$'}config" || exit 1
                    count=${'$'}((count + 1))
                done
                printf ']'
            """.trimIndent()
            var process: Process? = null
            return try {
                process = ProcessBuilder("su", "-M", "-c", script).redirectOutput(output)
                    .redirectError(File("/dev/null")).start()
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 ||
                    output.length() > MAX_CONFIG_BYTES + 128) return EMPTY
                HyperOs4LargeIconInputs(geometry, parse(output.readText(), prefixes))
            } catch (_: Exception) {
                EMPTY
            } finally {
                if (process?.isAlive == true) process.destroyForcibly()
                process?.inputStream?.close()
                process?.errorStream?.close()
                process?.outputStream?.close()
                output.delete()
            }
        }

        internal fun parse(text: String, prefixes: List<String>): List<Icon> {
            require(text.length <= MAX_CONFIG_BYTES + 128)
            val configurations = JSONArray(text)
            val entries = mutableMapOf<String, String>()
            val ambiguous = mutableSetOf<String>()
            for (i in 0 until configurations.length()) {
                val config = configurations.getJSONObject(i)
                for (key in config.keys()) {
                    val icon = config.optJSONObject(key) ?: continue
                    if (icon.optBoolean("isPairApp")) continue
                    val pkg = icon.optString("packageName")
                    val path = icon.optString("path").let { if (it.startsWith('/')) it else "/$it" }.trimEnd('/')
                    if (!ProfileIconRules.safeIconName("$pkg.png") || '.' !in pkg ||
                        path.length !in 2..980 || ".." in path || "//" in path || '\\' in path ||
                        path.any { it.code !in 32..126 } || prefixes.none { path.startsWith(it) && path.length > it.length }) continue
                    val previous = entries.putIfAbsent(path, pkg)
                    if (previous != null && previous != pkg) ambiguous += path
                }
            }
            require(entries.size <= 1365) // Three spans fit the native 4096-entry bound.
            return entries.filterKeys { it !in ambiguous }.map { Icon(it.key, it.value) }.sortedBy { it.path }
        }

        private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
