package com.jerrey.monoicon.safety

import android.content.Context
import android.content.pm.PackageManager
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.hook.HyperOs4Storage
import com.jerrey.monoicon.logging.logw
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** App-side status/recovery bridge. Launcher protection itself never requires root or this app. */
internal object LauncherSafety {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "MonoIcon-Safety").apply { isDaemon = true } }
    private var malformedState = false
    private var lastResetToken = "-"
    private var observedPid = 0
    private var readSucceeded = false

    /** A PID announcement can precede the launcher's very first module callback. */
    fun synchronizeStartup(context: Context, expectedPid: Int): Boolean {
        if (expectedPid <= 0) return synchronize(context)
        repeat(20) {
            val finished = synchronized(this) {
                val blocked = synchronize(context)
                if (blocked) return true
                !readSucceeded || observedPid == expectedPid
            }
            if (finished) return false
            Thread.sleep(500)
        }
        return ConfigManager.isSafetyBlockedFromUi()
    }

    fun refreshAndPublish(context: Context) {
        val app = context.applicationContext
        worker.execute {
            synchronize(app)
            HyperOs4PalettePublisher.publish(app)
        }
    }

    /** Called off the UI thread on app resume and every protected launcher-start wakeup. */
    @Synchronized fun synchronize(context: Context): Boolean {
        readSucceeded = false
        val uid = launcherUid(context) ?: return ConfigManager.isSafetyBlockedFromUi()
        val directory = HyperOs4Storage.directory(uid)
        val result = root(context, """
            set -e
            if [ -e '$directory/monoicon_safety_state.txt' ]; then
                head -c 1024 '$directory/monoicon_safety_state.txt'
            else
                printf 'ABSENT'
            fi
            printf '\nRESET\n'
            if [ -e '$directory/monoicon_safety_reset.txt' ]; then
                head -c 128 '$directory/monoicon_safety_reset.txt'
            else
                printf '%s' '-'
            fi
        """.trimIndent()) ?: return ConfigManager.isSafetyBlockedFromUi()
        val parts = result.split("\nRESET\n", limit = 2)
        readSucceeded = true
        observedPid = LauncherSafetyState.parse(parts[0])?.processId ?: 0
        malformedState = parts[0].trim() != "ABSENT" && LauncherSafetyState.parse(parts[0]) == null
        lastResetToken = parts.getOrNull(1)?.trim()?.takeIf(LauncherSafetyState::validToken) ?: "-"
        val blocked = parts.size != 2 || LauncherSafetyState.isBlocked(
            parts[0].trim().takeUnless { it == "ABSENT" }, parts.getOrNull(1)?.trim().orEmpty(),
        )
        if (blocked && !ConfigManager.isSafetyBlockedFromUi()) {
            ConfigManager.setSafetyBlocked(true)
            logw("MonoIcon.Safety", "Launcher startup safety tripped; module disabled until an explicit retry")
        }
        // Never clear the framework/local latch during an ordinary read or publication.
        return ConfigManager.isSafetyBlockedFromUi()
    }

    /** The existing master switch is the sole explicit recovery action; no safety switch exists. */
    @Synchronized fun enable(context: Context) {
        synchronize(context)
        if (ConfigManager.isSafetyBlockedFromUi()) {
            val uid = checkNotNull(launcherUid(context)) { "Launcher is unavailable" }
            val directory = HyperOs4Storage.directory(uid)
            val token = UUID.randomUUID().toString().replace("-", "")
            // An explicit retry repairs corruption to a canonical BLOCKED record,
            // then publishes the fresh retry token. It never deletes an armed record.
            val repair = if (!malformedState) "" else """
                boot=${'$'}(cat /proc/sys/kernel/random/boot_id)
                test ! -L '$directory/monoicon_safety_state.retry'
                printf 'MONOICON_SAFE 1 1 %s 1 1 0 0 %s\n' "${'$'}boot" '$lastResetToken' > '$directory/monoicon_safety_state.retry'
                chown $uid:$uid '$directory/monoicon_safety_state.retry'
                chmod 600 '$directory/monoicon_safety_state.retry'
                restorecon '$directory/monoicon_safety_state.retry'
                mv -f '$directory/monoicon_safety_state.retry' '$directory/monoicon_safety_state.txt'
            """.trimIndent()
            check(root(context, """
                set -e
                test -d '$directory'
                $repair
                test ! -L '$directory/monoicon_safety_reset.txt.new'
                printf '%s' '$token' > '$directory/monoicon_safety_reset.txt.new'
                chown $uid:$uid '$directory/monoicon_safety_reset.txt.new'
                chmod 600 '$directory/monoicon_safety_reset.txt.new'
                restorecon '$directory/monoicon_safety_reset.txt.new'
                sync
                mv -f '$directory/monoicon_safety_reset.txt.new' '$directory/monoicon_safety_reset.txt'
                sync
            """.trimIndent()) != null) { "Root access is required to retry safely" }
            ConfigManager.setSafetyBlocked(false)
        }
        ConfigManager.setEnabled(true)
    }

    fun launcherUid(context: Context): Int? = runCatching {
        context.packageManager.getApplicationInfo("com.miui.home", PackageManager.ApplicationInfoFlags.of(0)).uid
    }.getOrNull()

    /** Include immediately before our own kill. It cannot release an existing safety latch. */
    fun expectedRestartScript(uid: Int): String {
        val directory = HyperOs4Storage.directory(uid)
        return """
            monoicon_expect_restart() {
                safety='$directory/monoicon_safety_state.txt'
                expected='$directory/monoicon_safety_expected.txt'
                [ -e "${'$'}safety" ] || return 0
                read -r magic format blocked boot guardPid ticks armed healthy token < "${'$'}safety" || return 1
                [ "${'$'}magic" = MONOICON_SAFE ] && [ "${'$'}format" = 1 ] || return 1
                [ "${'$'}blocked" = 0 ] || return 0
                case "${'$'}guardPid:${'$'}ticks" in *[!0-9:]*|:*|*:) return 1 ;; esac
                [ "${'$'}(cat /proc/sys/kernel/random/boot_id)" = "${'$'}boot" ] || return 0
                [ -r "/proc/${'$'}guardPid/stat" ] || return 0
                [ "${'$'}(awk '{print ${'$'}22}' "/proc/${'$'}guardPid/stat")" = "${'$'}ticks" ] || return 0
                [ "${'$'}(tr '\000' '\n' < "/proc/${'$'}guardPid/cmdline" | head -n 1)" = com.miui.home ] || return 0
                test ! -L "${'$'}expected.new" || return 1
                printf '%s %s %s\n' "${'$'}boot" "${'$'}guardPid" "${'$'}ticks" > "${'$'}expected.new" &&
                    chown $uid:$uid "${'$'}expected.new" && chmod 600 "${'$'}expected.new" &&
                    restorecon "${'$'}expected.new" && mv -f "${'$'}expected.new" "${'$'}expected"
            }
            monoicon_expect_restart
        """.trimIndent()
    }

    private fun root(context: Context, script: String): String? {
        val output = File.createTempFile("safety-", ".txt", context.cacheDir)
        return try {
            val process = ProcessBuilder("su", "-M", "-c", script).redirectErrorStream(true).redirectOutput(output).start()
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS) || process.exitValue() != 0) null
                else output.readText().take(2048)
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
            }
        } catch (_: Exception) { null }
        finally { output.delete() }
    }
}
