package com.jerrey.monoicon.hook

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime-only trace probe for the HyperOS 4 launcher's Dart AOT icon pipeline.
 *
 * Why this exists: the production path for the launcher is a native patch against
 * one exact launcher build. When nothing visibly changes, logcat alone cannot
 * distinguish "the hook never installed" from "the hook installed but the icon
 * never travels that path", because most Dart icon functions do not log. This
 * probe answers that with execution counts instead of assumptions.
 *
 * It installs entry probes only. It never alters a return value, never builds a
 * Bitmap, and never touches the Dart heap. The native side refuses any launcher
 * build it was not generated for, and reports its own installation diagnostics.
 *
 * Scope: debug diagnostics. Production icon theming does not depend on this class
 * and it does not run unless the device build differs from the supported build.
 *
 * @see HyperOs4TraceProbe.nativeInstall
 */
@androidx.annotation.Keep
internal object HyperOs4TraceProbe {

    private const val TAG = "MonoIcon.Trace"

    /** [nativeInstall] result: libapp.so is not mapped in this process. */
    private const val RESULT_NO_LIBRARY = -1

    /** [nativeInstall] result: the platform refused executable memory. */
    private const val RESULT_NO_EXEC_MEMORY = -2

    /** [nativeInstall] result: an unknown launcher build; nothing was patched. */
    private const val RESULT_UNKNOWN_BUILD = -3

    private const val POLL_INTERVAL_MS = 5_000L
    private const val REPORT_COUNT = 6

    private val started = AtomicBoolean(false)

    /**
     * Loads the diagnostic library and arms the probes, then reports from a
     * background thread. Never throws: a failure here must not affect the
     * launcher or the production path.
     */
    fun install(apkPath: String, reason: String) {
        if (!started.compareAndSet(false, true)) return
        try {
            System.loadLibrary("monoicon_hyperos4_trace")
            val installed = nativeInstall(apkPath)
            val buildId = try {
                nativeBuildId()
            } catch (t: Throwable) {
                "<unavailable: ${t.javaClass.simpleName}>"
            }
            Log.i(
                TAG,
                "trace probe armed=$installed reason=$reason buildId=$buildId " +
                    "nodes=${nativeNodeCount()} ids=${nativeNodeIds()}",
            )
            when (installed) {
                RESULT_NO_LIBRARY -> {
                    Log.w(TAG, "libapp.so is not mapped; no probe installed")
                    return
                }
                RESULT_NO_EXEC_MEMORY -> {
                    Log.w(TAG, "executable memory denied; no probe installed")
                    return
                }
                RESULT_UNKNOWN_BUILD -> {
                    Log.w(TAG, "unknown launcher build id=$buildId; no probe installed")
                    return
                }
            }
            if (installed <= 0) return
            Thread({ reportLoop() }, "MonoIcon-TraceReport").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "trace probe unavailable: ${t.message}")
        }
    }

    private fun reportLoop() {
        val ids = nativeNodeIds().split(',')
        try {
            repeat(REPORT_COUNT) { round ->
                Thread.sleep(POLL_INTERVAL_MS)
                val snapshot = nativeSnapshot()
                val executed = ArrayList<String>()
                val silent = ArrayList<String>()
                var index = 0
                for (id in ids) {
                    val hits = snapshot[index * 3]
                    val caller = snapshot[index * 3 + 1]
                    val tid = snapshot[index * 3 + 2]
                    if (hits > 0) {
                        executed += "$id=$hits(caller=0x${java.lang.Long.toHexString(caller)} tid=$tid)"
                    } else {
                        silent += id
                    }
                    index++
                }
                Log.i(
                    TAG,
                    "round=${round + 1} EXECUTED[${executed.size}/${ids.size}] ${executed.joinToString(" ")}",
                )
                if (silent.isNotEmpty()) {
                    Log.i(TAG, "round=${round + 1} SILENT[${silent.size}] ${silent.joinToString(" ")}")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            Log.w(TAG, "trace report stopped: ${t.message}")
        }
    }

    private external fun nativeInstall(apkPath: String): Int
    private external fun nativeNodeCount(): Int
    private external fun nativeNodeIds(): String
    private external fun nativeSnapshot(): LongArray
    private external fun nativeBuildId(): String
}
