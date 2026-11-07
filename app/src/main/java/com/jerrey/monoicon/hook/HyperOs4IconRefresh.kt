package com.jerrey.monoicon.hook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileRepository
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logi
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Refreshes the launcher's existing icon cache only after the native commit. */
internal object HyperOs4IconRefresh {
    data class Restoration(
        val completion: Future<Boolean>,
        val profile: CompatibilityProfile,
        val launcherUid: Int,
        val largeSignature: String,
    )

    private data class Pending(
        val context: Context,
        val launcherUid: Int,
        val profile: CompatibilityProfile?,
        val content: String,
        val enabled: Boolean,
        val report: (String) -> Unit,
        val directory: String = HyperOs4Storage.directory(launcherUid),
    )

    private val latest = AtomicReference<Pending?>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MonoIcon-Hos4Refresh").apply { isDaemon = true }
    }

    fun schedule(
        context: Context,
        launcher: ApplicationInfo,
        profile: CompatibilityProfile?,
        content: String,
        enabled: Boolean,
        report: (String) -> Unit,
    ) {
        val pending = Pending(context.applicationContext ?: context, launcher.uid, profile, content, enabled, report)
        latest.set(pending)
        enqueue(pending)
    }

    /** The same immutable generation may need delivery to a new launcher PID. */
    fun afterLauncherRestart(context: Context): Future<Boolean>? {
        while (true) {
            val previous = latest.get() ?: return null
            val pending = previous.copy(context = context.applicationContext ?: context)
            if (latest.compareAndSet(previous, pending)) return enqueue(pending)
        }
    }

    /** A boot wakeup reuses the published generation; no wallpaper/export race. */
    fun restoreAfterLauncherRestart(context: Context): Restoration? {
        if (!ConfigManager.isEnabledFromUi()) return null
        val launcher = ProfileRepository.nativeLauncher(context) ?: return null
        val profile = ProfileRepository.resolve(context).profile ?: return null
        val primary = HyperOs4Storage.directory(launcher.uid)
        val legacy = HyperOs4Storage.legacyDirectory(launcher.uid)
        val output = File.createTempFile("hyperos4-boot-", ".txt", context.cacheDir)
        val verifyApproval = if (profile.match.systemVersions != null) "" else """
            [ "${'$'}(cat "${'$'}directory/monoicon_profiles/${profile.digest}.system" 2>/dev/null)" = '${ProfileRepository.systemApprovalToken()}' ] || exit 2
        """.trimIndent()
        val script = """
            directory=${quote(primary)}
            if [ ! -e "${'$'}directory/monoicon_palette.txt" ] && [ ! -L "${'$'}directory/monoicon_palette.txt" ]; then
                directory=${quote(legacy)}
            fi
            file="${'$'}directory/monoicon_palette.txt"
            $verifyApproval
            [ -f "${'$'}file" ] && [ ! -L "${'$'}file" ] || exit 2
            printf '%s\n' "${'$'}directory"
            head -c 256 "${'$'}file"
            printf '\nMONOICON_LARGE_SIGNATURE\n'
            read -r oldMagic oldVersion oldEnabled oldBackground oldForeground oldCircle oldLawnicons oldGeneration oldProfile < "${'$'}file" || exit 2
            [ "${'$'}{#oldGeneration}" -eq 34 ] || exit 2
            case "${'$'}oldGeneration" in g-*) ;; *) exit 2 ;; esac
            case "${'$'}{oldGeneration#g-}" in *[!0-9a-f]*) exit 2 ;; esac
            head -c 65 "${'$'}directory/monoicon_icons/${'$'}oldGeneration/.large-signature" 2>/dev/null || true
        """.trimIndent()
        var process: Process? = null
        try {
            val child = ProcessBuilder("su", "-M", "-c", script)
                .redirectErrorStream(true).redirectOutput(output).start()
            process = child
            if (!child.waitFor(10, TimeUnit.SECONDS) || child.exitValue() != 0) return null
            val text = output.readText()
            val directory = text.substringBefore('\n')
            if (directory != primary && directory != legacy) return null
            // Republish once to migrate an old CE-only installation for the
            // next Direct Boot. Subsequent restarts reuse DE without exporting.
            if (directory == legacy) return null
            val content = text.substringAfter('\n', "").substringBefore("\nMONOICON_LARGE_SIGNATURE\n")
            val fields = content.trim().split(Regex("\\s+"))
            if (content.length >= 256 || fields.size != 9 || fields[0] != "MONOICON" || fields[1] != "3" ||
                fields[2] != "1" || fields[8] != profile.digest ||
                fields[5] != (if (ConfigManager.isCircleIconsEnabledFromUi()) "1" else "0") ||
                fields[6] != (if (ConfigManager.isLawniconsEnabledFromUi()) "1" else "0") ||
                !Regex("g-[a-f0-9]{32}").matches(fields[7])) return null
            val publishedSignature = text.substringAfter("\nMONOICON_LARGE_SIGNATURE\n", "").trim()
            if (profile.icons.largeIconPrefixes.isNotEmpty()) {
                val current = HyperOs4LargeIconInputs.read(context, profile, launcher.uid)
                // Initialization can temporarily hide bridge/config data. Keep
                // the committed generation then; regenerate only from a complete
                // new snapshot, including after a grid or icon-scale change.
                if (current.geometry != null && current.signature != publishedSignature) return null
                if (current.geometry == null && publishedSignature == HyperOs4LargeIconInputs.EMPTY.signature) return null
            }
            val pending = Pending(context.applicationContext ?: context, launcher.uid, profile, content, true, {}, directory)
            val completion = if (latest.compareAndSet(null, pending)) enqueue(pending)
                else afterLauncherRestart(context) ?: return null
            return Restoration(completion, profile, launcher.uid, publishedSignature)
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                it.inputStream.close()
                it.errorStream.close()
                it.outputStream.close()
            }
            output.delete()
        }
    }

    /**
     * A boot-time metric request may precede native/Dart readiness. Check again
     * after the restored generation has been acknowledged and refreshed, while
     * the startup job still keeps the module runnable. Unsupported devices keep
     * their valid generation after this bounded retry instead of looping jobs.
     */
    fun needsPublicationAfterRestore(context: Context, restored: Restoration): Boolean {
        if (restored.profile.icons.geometryBridge == null) return false
        repeat(3) { attempt ->
            if (!ConfigManager.isEnabledFromUi() ||
                ProfileRepository.resolve(context).profile?.digest != restored.profile.digest) return true
            val current = HyperOs4LargeIconInputs.read(context, restored.profile, restored.launcherUid)
            if (current.geometry != null) return current.signature != restored.largeSignature
            if (attempt < 2) Thread.sleep(400)
        }
        return false
    }

    private fun enqueue(pending: Pending): Future<Boolean> =
        worker.submit<Boolean> {
            if (latest.get() !== pending) return@submit true
            runCatching { refresh(pending) }.getOrElse { failure ->
                report(pending, "Icon refresh failed: ${failure.message ?: failure.javaClass.simpleName}")
                false
            }
        }

    private fun report(pending: Pending, message: String) {
        if (latest.get() === pending) {
            logi("MonoIcon.HyperOS4", message)
            pending.report(message)
        }
    }

    private fun refresh(pending: Pending): Boolean {
        val fields = pending.content.trim().split(Regex("\\s+"))
        require(fields.size == 9 && fields[0] == "MONOICON" && fields[1] == "3") { "Invalid Profile commit" }
        require(fields[2] == if (pending.enabled) "1" else "0") { "Commit enabled state changed" }
        val generation = fields[7]
        val digest = fields[8]
        require(Regex("g-[a-f0-9]{32}").matches(generation) && Regex("[a-f0-9]{64}").matches(digest)) {
            "Invalid Profile generation or digest"
        }
        require(pending.launcherUid >= 0) { "Invalid launcher UID" }
        val userId = pending.launcherUid / 100_000
        val directory = pending.directory
        val token = UUID.randomUUID().toString()
        val expected = File(pending.context.cacheDir, "hyperos4-refresh-$token.expected")
        val output = File(pending.context.cacheDir, "hyperos4-refresh-$token.log")
        val refresh = pending.profile?.refresh
        val action = refresh?.action.orEmpty()
        val packageExtra = refresh?.packageExtra.orEmpty()
        expected.writeText(pending.content, Charsets.UTF_8)

        // The root child owns the wait and delivery loop. It can finish even if
        // Android freezes the settings app after the user returns to the home.
        val script = """
            expected=${quote(expected.path)}
            palette=${quote("$directory/monoicon_palette.txt")}
            applied=${quote("$directory/monoicon_palette.applied")}
            status=${quote("$directory/monoicon_hyos_palette.txt")}
            packages=${quote("$directory/monoicon_icons/$generation/.packages")}
            markerFile=${quote("$directory/monoicon_refresh.applied")}
            markerStage=${quote("$directory/monoicon_refresh.applied.$token.new")}
            action=${quote(action)}
            packageExtra=${quote(packageExtra)}
            enabled=${if (pending.enabled) 1 else 0}
            expectedResult=${if (pending.enabled) 1 else 12}
            hasRefresh=${if (refresh != null) 1 else 0}
            trap 'rm -f "${'$'}markerStage"' EXIT

            result() {
                printf 'MONOICON_REFRESH|%s\n' "${'$'}1"
            }
            current_pid() {
                currentPid=${'$'}(pidof com.miui.home 2>/dev/null) || return 1
                case "${'$'}currentPid" in ''|*[!0-9]*) return 1 ;; esac
                printf '%s' "${'$'}currentPid"
            }
            same_commit() {
                cmp -s "${'$'}palette" "${'$'}expected"
            }
            same_process() {
                same_commit && [ "${'$'}(current_pid)" = "${'$'}pid" ]
            }
            native_ready() {
                cmp -s "${'$'}applied" "${'$'}expected" || return 1
                awk -v wantedPid="${'$'}pid" -v wantedResult="${'$'}expectedResult" '
                    {
                        for (i = 1; i <= NF; i++) {
                            split(${'$'}i, field, "=")
                            if (field[1] == "pid") process = field[2]
                            if (field[1] == "result") status = field[2]
                        }
                    }
                    END { exit !(process == wantedPid && status == wantedResult) }
                ' "${'$'}status" 2>/dev/null
            }
            receiver_ready() {
                dumpsys -t 1 activity broadcasts 2>/dev/null | awk -v wantedPid="${'$'}pid" -v wantedAction="${'$'}action" '
                    BEGIN { app = "app=" wantedPid ":com.miui.home"; action = "Action: \"" wantedAction "\"" }
                    /\* ReceiverList/ {
                        inReceiver = 1
                        current = 0
                        headerLine = NR
                        headerIndent = match(${'$'}0, /[^ \t]/) - 1
                    }
                    inReceiver {
                        indent = match(${'$'}0, /[^ \t]/) - 1
                        if (NR != headerLine && indent >= 0 && indent <= headerIndent) {
                            inReceiver = 0
                            current = 0
                        }
                        if (inReceiver) {
                            at = index(${'$'}0, app)
                            if (at > 0) {
                                boundary = substr(${'$'}0, at + length(app), 1)
                                if (boundary == "" || boundary == "/" || boundary == " " || boundary == "}" || boundary == ",") current = 1
                            }
                            if (current && index(${'$'}0, action) > 0) found = 1
                        }
                    }
                    END { exit !found }
                '
            }
            mark_applied() {
                printf '%s' "${'$'}marker" > "${'$'}markerStage" &&
                    chown ${pending.launcherUid}:${pending.launcherUid} "${'$'}markerStage" &&
                    chmod 600 "${'$'}markerStage" &&
                    restorecon "${'$'}markerStage" &&
                    mv -f "${'$'}markerStage" "${'$'}markerFile"
            }

            deadline=${'$'}(( ${'$'}(date +%s) + 45 ))
            ready=0
            while [ "${'$'}(date +%s)" -lt "${'$'}deadline" ]; do
                if ! same_commit; then result superseded; exit 20; fi
                pid=${'$'}(current_pid) || pid=
                if [ -n "${'$'}pid" ] && native_ready; then
                    if [ "${'$'}enabled" -eq 0 ] || [ "${'$'}hasRefresh" -eq 0 ] || receiver_ready; then
                        ready=1
                        break
                    fi
                fi
                sleep 0.25
            done
            if [ "${'$'}ready" -ne 1 ]; then result waiting_timeout; exit 10; fi
            if ! same_process || ! native_ready; then result superseded; exit 20; fi
            marker="${'$'}pid|$generation|$digest"
            if [ "${'$'}(cat "${'$'}markerFile" 2>/dev/null)" = "${'$'}marker" ]; then
                result already_requested
                exit 0
            fi

            if [ "${'$'}enabled" -eq 0 ]; then
                writes=${'$'}(awk '
                    { for (i = 1; i <= NF; i++) { split(${'$'}i, field, "="); if (field[1] == "words_written") value = field[2] } }
                    END { print value }
                ' "${'$'}status")
                case "${'$'}writes" in ''|*[!0-9]*) result invalid_native_status; exit 11 ;; esac
                if ! same_process || ! native_ready; then result superseded; exit 20; fi
                if [ "${'$'}writes" -gt 0 ]; then
                    processName=${'$'}(tr '\000' '\n' < "/proc/${'$'}pid/cmdline" | head -n 1)
                    if [ "${'$'}processName" != com.miui.home ]; then result superseded; exit 20; fi
                    if ! same_process || ! native_ready; then result superseded; exit 20; fi
                    ${com.jerrey.monoicon.safety.LauncherSafety.expectedRestartScript(pending.launcherUid)}
                    if [ "${'$'}?" -ne 0 ]; then result restart_failed; exit 13; fi
                    if ! kill "${'$'}pid"; then
                        rm -f ${quote(HyperOs4Storage.directory(pending.launcherUid) + "/monoicon_safety_expected.txt")}
                        result restart_failed; exit 13
                    fi
                    if ! mark_applied; then result marker_failed; exit 12; fi
                    result disabled_restart_requested
                else
                    if ! mark_applied; then result marker_failed; exit 12; fi
                    result disabled_ready
                fi
                exit 0
            fi

            if [ "${'$'}hasRefresh" -eq 0 ]; then
                result manual_restart
                exit 0
            fi
            if [ ! -f "${'$'}packages" ] || [ -L "${'$'}packages" ]; then result packages_missing; exit 14; fi
            count=0
            while IFS= read -r packageName || [ -n "${'$'}packageName" ]; do
                [ "${'$'}{#packageName}" -le 255 ] || continue
                printf '%s\n' "${'$'}packageName" | grep -Eq '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+${'$'}' || continue
                if ! same_process || ! native_ready; then result superseded; exit 20; fi
                if ! am broadcast --user $userId -a "${'$'}action" -p com.miui.home --es "${'$'}packageExtra" "${'$'}packageName"; then
                    result broadcast_failed
                    exit 15
                fi
                count=${'$'}((count + 1))
            done < "${'$'}packages"
            if [ "${'$'}count" -eq 0 ]; then result packages_empty; exit 14; fi
            if ! same_process || ! native_ready; then result superseded; exit 20; fi
            if ! mark_applied; then result marker_failed; exit 12; fi
            result "requested|${'$'}count"
        """.trimIndent()

        var process: Process? = null
        try {
            if (latest.get() !== pending) return true
            process = ProcessBuilder("su", "-M", "-c", script)
                .redirectErrorStream(true).redirectOutput(output).start()
            val completed = process.waitFor(60, TimeUnit.SECONDS)
            if (latest.get() !== pending) return true
            if (!completed) {
                report(pending, "Icon refresh timed out; restart the launcher to retry")
                return false
            }
            val log = output.readText()
            val result = log.lineSequence().lastOrNull { it.startsWith("MONOICON_REFRESH|") }
                ?.removePrefix("MONOICON_REFRESH|")
            when {
                result == "superseded" -> Unit
                result?.startsWith("requested|") == true && process.exitValue() == 0 ->
                    report(pending, "Icon refresh requested for ${result.substringAfter('|')} packages")
                result == "already_requested" && process.exitValue() == 0 ->
                    report(pending, "Icon refresh already requested for this launcher process and generation")
                result == "disabled_restart_requested" && process.exitValue() == 0 ->
                    report(pending, "Native icons restored; launcher restart requested to remove cached themed icons")
                result == "disabled_ready" && process.exitValue() == 0 ->
                    report(pending, "Native icons disabled; launcher has no applied theme instructions")
                result == "manual_restart" && process.exitValue() == 0 -> {
                    pending.profile?.let {
                        pending.context.contentResolver.notifyChange(Settings.System.getUriFor(it.settingsKey), null, 0)
                    }
                    report(pending, "Profile has no icon refresh broadcast; restart the launcher to refresh cached icons")
                }
                result == "waiting_timeout" ->
                    report(pending, "Waiting for the current launcher to apply the configuration and register its icon receiver; restart the launcher to retry")
                else -> report(pending, "Icon refresh incomplete (${result ?: "root exit=${process.exitValue()}"}): ${log.takeLast(800)}")
            }
            return result == "superseded" || (process.exitValue() == 0 &&
                (result?.startsWith("requested|") == true || result in setOf(
                    "already_requested", "disabled_restart_requested", "disabled_ready", "manual_restart",
                )))
        } finally {
            process?.let {
                if (it.isAlive) it.destroyForcibly()
                it.inputStream.close()
                it.errorStream.close()
                it.outputStream.close()
            }
            expected.delete()
            output.delete()
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
