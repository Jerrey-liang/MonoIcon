package com.jerrey.monoicon.hook

import android.content.Context
import android.os.Looper
import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.logging.logi
import com.jerrey.monoicon.logging.logw
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phone layout metrics requested from the launcher's existing native FuncBridge.
 *
 * 6144: FuncBridge.handleGetCellWidth/Height return the current grid doubles in
 * `result`. getIconScales exposes the same scale used by
 * IconConfigHandler.calIconSize: normalIconSize = cellWidth * iconScale.
 * GridController.getMiuiWidgetSizeSpec then supplies the large icon dimensions;
 * its added title padding is removed by launcherBigIconHeight.
 *
 * These are private bridge methods, not public ContentProvider.call methods.
 * The verified native worker reads their scalar Bundles and answers a request
 * scoped to a nonce, launcher PID and profile digest in the launcher's DE files.
 *
 * The tablet path uses a different padIconSize/percentage calculation. Missing,
 * changing or unsupported metrics return null so the original icon stays usable.
 */
internal object HyperOs4LargeIconGeometry {
    private val HEX_32 = Regex("[a-f0-9]{32}")
    private val HEX_64 = Regex("[a-f0-9]{64}")
    private val diagnostics = HyperOs4LargeIconDiagnostics()
    private var lastGeometrySignature: String? = null

    data class Size(val width: Double, val height: Double)

    data class Geometry(val cellWidth: Double, val cellHeight: Double, val normalIconSize: Double) {
        val signature: String
            get() = listOf(cellWidth, cellHeight, normalIconSize)
                .joinToString(":", prefix = "phone-grid-v1:") { java.lang.Double.toHexString(it) }

        fun dimensions(spanX: Int, spanY: Int): Size? {
            if (spanX !in 1..2 || spanY !in 1..2) return null
            if (spanX == 1 && spanY == 1) return Size(normalIconSize, normalIconSize)
            val width = (spanX - 2) * cellWidth + cellHeight + normalIconSize
            val height = (spanY - 1) * cellHeight + normalIconSize
            return if (validMetric(width) && validMetric(height)) Size(width, height) else null
        }
    }

    /** Read on a worker; a launcher still initializing must not stall publication. */
    @Synchronized
    fun read(context: Context, profile: CompatibilityProfile, launcherUid: Int): Geometry? {
        if (Looper.myLooper() == Looper.getMainLooper()) return unavailable("main-thread")
        if (profile.icons.geometryBridge == null) return unavailable("profile-bridge-missing")
        if (launcherUid < 0 || !HEX_64.matches(profile.digest)) return unavailable("invalid-request-identity")
        if (context.resources.configuration.smallestScreenWidthDp !in 1..599) return unavailable("unsupported-screen-width")
        val nonce = UUID.randomUUID().toString().replace("-", "")
        var output: File? = null
        var process: Process? = null
        return try {
            val supported = supportedPhone(
                context.resources.configuration.smallestScreenWidthDp,
                systemProperty("ro.build.characteristics"),
                systemProperty("persist.sys.multi_display_type"),
                systemProperty("persist.sys.muiltdisplay_type"),
            )
            if (!supported) return unavailable("unsupported-device-layout")
            val directory = HyperOs4Storage.directory(launcherUid)
            val script = """
                directory=${quote(directory)}
                [ -d "${'$'}directory" ] && [ ! -L "${'$'}directory" ] || exit 2
                pid=${'$'}(pidof com.miui.home 2>/dev/null) || exit 4
                case "${'$'}pid" in ''|*[!0-9]*) exit 4 ;; esac
                request="${'$'}directory/monoicon_geometry.request"
                response="${'$'}directory/monoicon_geometry.response"
                stage="${'$'}request.$nonce.new"
                trap 'rm -f "${'$'}stage"' EXIT
                [ ! -e "${'$'}stage" ] && [ ! -L "${'$'}stage" ] || exit 5
                printf 'MONOICON_GEOMETRY 1 %s ${profile.digest} $nonce\n' "${'$'}pid" > "${'$'}stage" || exit 5
                chmod 600 "${'$'}stage" && chown $launcherUid:$launcherUid "${'$'}stage" && restorecon "${'$'}stage" && mv -f "${'$'}stage" "${'$'}request" || exit 5
                count=0
                while [ "${'$'}count" -lt 30 ]; do
                    if [ -f "${'$'}response" ] && [ ! -L "${'$'}response" ]; then
                        line=${'$'}(head -c 768 "${'$'}response")
                        case "${'$'}line" in
                            "MONOICON_GEOMETRY 1 ${'$'}pid ${profile.digest} $nonce "*)
                                [ "${'$'}(pidof com.miui.home 2>/dev/null)" = "${'$'}pid" ] || exit 6
                                printf 'MONOICON_GEOMETRY_PID %s\n%s\n' "${'$'}pid" "${'$'}line"
                                exit 0 ;;
                        esac
                    fi
                    count=${'$'}((count + 1))
                    sleep 0.1
                done
                exit 3
            """.trimIndent()
            val file = File.createTempFile("hyperos4-geometry-", ".txt", context.cacheDir).also { output = it }
            val child = ProcessBuilder("su", "-M", "-c", script).redirectOutput(file)
                .redirectError(File("/dev/null")).start().also { process = it }
            if (!child.waitFor(5, TimeUnit.SECONDS)) return unavailable("root-process-timeout")
            if (child.exitValue() != 0) return unavailable(when (child.exitValue()) {
                2 -> "storage-unavailable"
                3 -> "response-timeout"
                4 -> "launcher-pid-unavailable"
                5 -> "request-write-failed"
                6 -> "launcher-restarted"
                else -> "root-process-failed"
            }, "exit=${child.exitValue()}")
            if (file.length() > 900) return unavailable("response-too-large")
            val text = file.readText()
            val header = text.substringBefore('\n').split(' ')
            if (header.size != 2 || header[0] != "MONOICON_GEOMETRY_PID") return unavailable("invalid-response-header")
            val pid = header[1].toIntOrNull()?.takeIf { it > 0 } ?: return unavailable("invalid-response-pid")
            val geometry = parseResponse(text.substringAfter('\n'), pid, profile.digest, nonce)
                ?: return unavailable("invalid-response-metrics", "pid=$pid")
            if (lastGeometrySignature != geometry.signature) {
                lastGeometrySignature = geometry.signature
                logi("MonoIcon.HyperOS4", "large icon geometry: cell=${geometry.cellWidth}x${geometry.cellHeight} icon=${geometry.normalIconSize}")
            }
            geometry
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unavailable("interrupted")
        } catch (failure: Exception) {
            unavailable("read-exception", failure.javaClass.simpleName)
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            process?.inputStream?.close()
            process?.errorStream?.close()
            process?.outputStream?.close()
            output?.delete()
        }
    }

    private fun unavailable(reason: String, detail: String = ""): Geometry? {
        diagnostics.report("geometry-$reason", detail)
        return null
    }

    /** Reject stale, mismatched, malformed or nonfinite native responses. */
    fun parseResponse(text: String, pid: Int, digest: String, nonce: String): Geometry? {
        if (text.length > 768 || pid <= 0 || !HEX_64.matches(digest) || !HEX_32.matches(nonce)) return null
        val fields = text.trim().split(Regex("\\s+"))
        if (fields.size != 11 || fields[0] != "MONOICON_GEOMETRY" || fields[1] != "1" ||
            fields[2] != pid.toString() || fields[3] != digest || fields[4] != nonce) return null
        val values = fields.drop(5).map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
        return fromProviderMetrics(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun systemProperty(name: String): String {
        val process = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0)
        return value
    }

    /** MIUI/miuix.os.Build uses these two display-type properties for foldables. */
    fun supportedPhone(
        smallestWidthDp: Int,
        characteristics: String,
        displayType: String,
        legacyDisplayType: String,
    ): Boolean {
        if (smallestWidthDp !in 1..599 || characteristics.contains("tablet", ignoreCase = true)) return false
        val modern = displayType.toIntOrNull() ?: if (displayType.isBlank()) 1 else return false
        val legacy = legacyDisplayType.toIntOrNull() ?: if (legacyDisplayType.isBlank()) 0 else return false
        // Both foldable and other multi-display form factors use layout branches
        // outside the phone formula verified here, including their outer screen.
        return modern in 0..1 && legacy == 0
    }

    /** The launcher's orientation is authoritative; the module activity may differ. */
    fun fromProviderMetrics(
        cellWidth: Double,
        cellHeight: Double,
        portraitWidth: Double,
        landscapeWidth: Double,
        portraitScale: Double,
        landscapeScale: Double,
    ): Geometry? {
        if (!validMetric(cellWidth) || !validMetric(cellHeight)) return null
        val portrait = sameMetric(cellWidth, portraitWidth) && validScale(portraitScale)
        val landscape = sameMetric(cellWidth, landscapeWidth) && validScale(landscapeScale)
        val scale = when {
            portrait && landscape -> {
                if (!sameMetric(portraitScale, landscapeScale)) return null
                portraitScale
            }
            portrait -> portraitScale
            landscape -> landscapeScale
            else -> return null
        }
        val size = cellWidth * scale
        if (!validMetric(size)) return null
        val geometry = Geometry(cellWidth, cellHeight, size)
        if (geometry.dimensions(1, 2) == null || geometry.dimensions(2, 1) == null) return null
        return geometry
    }

    private fun validMetric(value: Double): Boolean = value.isFinite() && value in 1.0..8192.0
    private fun validScale(value: Double): Boolean = value.isFinite() && value in 0.05..2.0
    private fun sameMetric(first: Double, second: Double): Boolean =
        first.isFinite() && second.isFinite() &&
            kotlin.math.abs(first - second) <= maxOf(1.0, kotlin.math.abs(first)) * 1e-6
}

/** Repeated publications report each failure stage once per process, with a hard cap. */
internal class HyperOs4LargeIconDiagnostics {
    private val reported = mutableSetOf<String>()

    @Synchronized
    fun report(reason: String, detail: String = "") {
        if (reported.size < 24 && reported.add(reason)) {
            logw("MonoIcon.HyperOS4", "large icon input unavailable: $reason ${detail.take(160)}".trimEnd())
        }
    }
}
