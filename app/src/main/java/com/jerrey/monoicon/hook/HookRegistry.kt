package com.jerrey.monoicon.hook

import java.util.concurrent.ConcurrentHashMap

/**
 * Hook installation registry (Phase 4.0-C).
 *
 * Centralizes hook registration, installation outcome reporting and
 * launcher-version compatibility visibility. Every hook declares a stable
 * name and whether it is [required] for MonoIcon's core function:
 *
 * - **required** hooks: failure degrades functionality but must never
 *   crash the launcher (same protective behavior as before).
 * - **optional** hooks (e.g. the Kotlin-synthetic 1x1 folder lambda,
 *   sensitive to launcher build changes): failure is reported in the
 *   status line and does not affect startup at all.
 *
 * ## Output
 * [statusReport] produces the `Hook status:` listing, logged at INFO on
 * every launcher start (production-permitted category: hook availability).
 */
object HookRegistry {

    /** One installed hook's outcome. */
    data class HookEntry(
        val name: String,
        val required: Boolean,
        val installed: Boolean,
    )

    private val entries = ConcurrentHashMap<String, HookEntry>()

    /** Total registered hooks. */
    val size: Int get() = entries.size

    /** Number of successfully installed hooks. */
    val installedCount: Int get() = entries.values.count { it.installed }

    /**
     * Registers and installs one hook.
     *
     * @param name Stable hook name (status output).
     * @param required Whether the hook is required for core functionality.
     * @param install The installation action.
     * @return true if installed.
     */
    fun install(name: String, required: Boolean, install: () -> Unit): Boolean {
        val ok = try {
            install()
            true
        } catch (e: ClassNotFoundException) {
            android.util.Log.w(TAG, "  ✗ $name: class not found — ${e.message}")
            false
        } catch (e: NoSuchMethodException) {
            android.util.Log.w(TAG, "  ✗ $name: method not found — ${e.message}")
            false
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "  ✗ $name: ${e.javaClass.simpleName} — ${e.message}", e)
            false
        }
        entries[name] = HookEntry(name, required, ok)
        if (ok) {
            android.util.Log.i(TAG, "  ✓ $name${if (required) "" else " (optional)"}")
        }
        return ok
    }

    /** Multi-line `Hook status:` report (INFO category: hook availability). */
    fun statusReport(): String {
        val sb = StringBuilder("Hook status:")
        entries.values.sortedBy { it.name }.forEach { e ->
            sb.append("\n  ").append(e.name)
                .append("  ")
                .append(if (e.installed) "OK" else if (e.required) "MISSING(required)" else "MISSING(optional)")
        }
        sb.append("\n  total=$installedCount/$size")
        return sb.toString()
    }

    private const val TAG = "MonoIcon.Hook"
}
