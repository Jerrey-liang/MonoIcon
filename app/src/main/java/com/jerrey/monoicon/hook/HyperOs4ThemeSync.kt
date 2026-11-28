package com.jerrey.monoicon.hook

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.logging.logw
import com.jerrey.monoicon.theme.archive.ThemeArchiveConflictException
import com.jerrey.monoicon.theme.archive.ThemeArchiveInstaller
import com.jerrey.monoicon.theme.archive.ThemeArchiveStatus
import com.jerrey.monoicon.theme.wallpaper.WallpaperSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** App-process owner. Job work items survive process death and are acknowledged after commit. */
internal object HyperOs4ThemeSync {
    const val SCAN = "scan"
    const val STYLE = "style"
    const val WALLPAPER = "wallpaper"
    const val PACKAGE = "package"
    const val VALIDATION = "validation"
    const val CHECK = "integrity"
    const val LARGE = "large-icons"
    private const val RECOVER = "recover"
    private const val ADOPT = "adopt"
    private const val PREFS = "hyperos4-theme-sync"
    private const val JOB = 0x4d495448
    private val mutableStatus = MutableStateFlow("waiting")
    private val mutableConflict = MutableStateFlow(false)
    private val mutableRecoverable = MutableStateFlow(false)
    val canRecoverOriginal = mutableRecoverable.asStateFlow()
    val status = mutableStatus.asStateFlow()
    val conflict = mutableConflict.asStateFlow()

    fun init(app: Context) {
        if (!HyperOs4Environment.isSupported(app)) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutableStatus.value = prefs.getString("status", "waiting") ?: "waiting"
        mutableConflict.value = prefs.getBoolean("conflict", false)
        mutableRecoverable.value = prefs.getBoolean("recoverable", false)
        ConfigManager.addOnConfigChangedListener { key ->
            if (key != "ui_language" && key != "notification_icons" &&
                (ConfigManager.xposedService() != null || key == "enabled" && !ConfigManager.isEnabledFromUi())) {
                enqueue(app, STYLE)
            }
        }
        WallpaperSourceRepository.addListener {
            if (ConfigManager.xposedService() != null) enqueue(app, STYLE)
        }
        if (ConfigManager.xposedService() != null) enqueue(app, STYLE)
    }

    // After JobScheduler's bounded retry window, the next event (or return from a root
    // permission prompt) must reconcile all packages, including the failed batch.
    fun needsReconcile(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("needsScan", false)

    fun markForReconcile(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).save { putBoolean("needsScan", true) }
    }

    fun acceptCurrentTheme(context: Context) {
        if (conflict.value) enqueue(context, ADOPT)
    }

    fun recoverOriginal(context: Context) {
        if (canRecoverOriginal.value) enqueue(context, RECOVER)
    }

    fun enqueue(context: Context, reason: String, packageName: String? = null) {
        val intent = Intent(reason).putExtra("package", packageName)
        val info = JobInfo.Builder(JOB, ComponentName(context, HyperOs4ThemeJob::class.java))
            .setOverrideDeadline(1500).build()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        runCatching { check(scheduler.enqueue(info, JobWorkItem(intent)) == JobScheduler.RESULT_SUCCESS) }
            .onFailure { report(context, "error", "JobScheduler: ${it.message}") }
    }

    private fun report(context: Context, state: String, detail: String = "") {
        val message = if (detail.isBlank()) state else "$state\n$detail"
        mutableStatus.value = message
        mutableConflict.value = state == "conflict" || state == "rejected"
        mutableRecoverable.value = state == "conflict" && runCatching {
            ThemeArchiveInstaller.canRecoverMissingOriginal(context)
        }.getOrDefault(false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("status", message)
            putBoolean("conflict", mutableConflict.value)
            putBoolean("recoverable", mutableRecoverable.value)
        }
    }

    private inline fun SharedPreferences.save(update: SharedPreferences.Editor.() -> Unit) {
        check(edit().apply(update).commit()) { "Could not commit the theme sync state" }
    }

    private fun SharedPreferences.clearSyncState() = save {
        remove("signature")
        remove("needsScan")
        remove("pausedByValidation")
    }

    /** Serialized by the JobService executor. Interrupted deliveries stay pending. */
    fun run(context: Context, intents: List<Intent>, active: () -> Boolean): Boolean {
        if (!HyperOs4Environment.isSupported(context)) return true
        val requests = intents.mapNotNull { it.action }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val packages = intents.filter { it.action == PACKAGE }.mapNotNull { it.getStringExtra("package") }.toSet()
        return try {
            val state = ThemeArchiveInstaller.status(context)
            val inspectOnly = requests.all { it == CHECK || it == VALIDATION }
            if (RECOVER in requests && state == ThemeArchiveStatus.CONFLICT &&
                ThemeArchiveInstaller.canRecoverMissingOriginal(context)) {
                ConfigManager.setEnabled(false)
                val large = ThemeArchiveInstaller.restoreLargeIcons(context)
                val result = ThemeArchiveInstaller.recoverMissingOriginal(context)
                prefs.clearSyncState()
                val refreshed = result.refreshSucceeded && large.refreshSucceeded
                report(context, if (refreshed) "inactive" else "refresh")
                return refreshed
            }
            if (!inspectOnly && !ConfigManager.isEnabledFromUi()) {
                val large = ThemeArchiveInstaller.restoreLargeIcons(context)
                val result = ThemeArchiveInstaller.restore(context)
                prefs.clearSyncState()
                val refreshed = result.refreshSucceeded && large.refreshSucceeded
                report(context, if (refreshed) "inactive" else "refresh")
                return refreshed
            }
            if (ADOPT in requests) {
                if (state == ThemeArchiveStatus.CONFLICT) ThemeArchiveInstaller.acceptCurrentTheme(context)
                prefs.save { remove("signature"); remove("pausedByValidation") }
            } else if (state == ThemeArchiveStatus.CONFLICT && VALIDATION in requests) {
                // ThemeReceiver has already rejected our exact managed ZIP. Restore only a
                // verified, now-missing baseline (or relinquish an already-restored baseline).
                // Persist the pause first so a process death cannot reinstall the rejected ZIP.
                prefs.save { putBoolean("pausedByValidation", true); remove("signature") }
                val large = ThemeArchiveInstaller.restoreLargeIcons(context)
                val rollback = if (ThemeArchiveInstaller.canRecoverMissingOriginal(context))
                    ThemeArchiveInstaller.recoverMissingOriginal(context) else ThemeArchiveInstaller.restore(context)
                report(context, "rejected")
                return rollback.refreshSucceeded && large.refreshSucceeded
            } else if (state == ThemeArchiveStatus.CONFLICT) {
                val large = ThemeArchiveInstaller.restoreLargeIcons(context)
                report(context, "conflict", if (mutableConflict.value) mutableStatus.value.substringAfter('\n', "") else "")
                return large.refreshSucceeded
            } else if (prefs.getBoolean("pausedByValidation", false)) {
                // Finish any pending refresh from a recovered transaction. An external new
                // theme stays untouched until the user asks to apply MonoIcon to it.
                val complete = ThemeArchiveInstaller.restoreLargeIcons(context).refreshSucceeded &&
                    (state != ThemeArchiveStatus.INACTIVE || ThemeArchiveInstaller.restore(context).refreshSucceeded)
                report(context, "rejected")
                return complete
            }
            if (inspectOnly) {
                // Our own atomic rename also reaches the observer. Preserve pending refresh errors.
                if (mutableStatus.value.substringBefore('\n') !in setOf("error", "refresh")) {
                    report(context, if (state == ThemeArchiveStatus.ACTIVE) "active" else "inactive")
                }
                return true
            }
            WallpaperSourceRepository.refreshAndAwait(context, force = WALLPAPER in requests)
            val signature = HyperOs4ThemeRenderer.signature(context)
            val full = needsReconcile(context) || state != ThemeArchiveStatus.ACTIVE || SCAN in requests || ADOPT in requests ||
                prefs.getString("signature", null) != signature
            val updateArchive = full || packages.isNotEmpty() || mutableStatus.value != "active"
            if (!updateArchive && LARGE !in requests) return true
            if (!active()) return false
            val directory = File(context.cacheDir, "theme-archive-render-" + UUID.randomUUID())
            try {
                check(directory.mkdir())
                fun current() = active() && ConfigManager.isEnabledFromUi()
                if (updateArchive) {
                    val icons = HyperOs4ThemeRenderer.render(context, directory, if (full) null else packages, active)
                        ?: return false
                    if (!current() || signature != HyperOs4ThemeRenderer.signature(context)) return false
                    if (!ThemeArchiveInstaller.apply(context, icons, full).refreshSucceeded) {
                        report(context, "refresh")
                        return false
                    }
                }
                val large = HyperOs4ThemeLargeIcons.apply(context, directory, ::current) ?: return false
                if (large.refreshSucceeded) {
                    prefs.save {
                        putString("signature", signature)
                        if (full) remove("needsScan")
                    }
                    report(context, "active")
                } else report(context, "refresh")
                large.refreshSucceeded
            } finally {
                directory.deleteRecursively()
            }
        } catch (failure: ThemeArchiveConflictException) {
            report(context, "conflict", failure.message.orEmpty())
            true
        } catch (failure: Exception) {
            report(context, "error", failure.message ?: failure.javaClass.simpleName)
            logw("MonoIcon.Theme", "Archive synchronization failed: ${failure.message}")
            false
        }
    }
}
