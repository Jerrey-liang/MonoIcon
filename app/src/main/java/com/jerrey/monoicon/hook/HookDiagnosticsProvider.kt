package com.jerrey.monoicon.hook

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Only same-user, real launcher/SystemUI callers may publish their own diagnostic snapshot. */
class HookDiagnosticsProvider : ContentProvider() {
    private val snapshotLock = Any()
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == HookDiagnostics.METHOD_REPORT && arg == null)
        val app = context ?: error("Diagnostics unavailable")
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        if (uid / 100_000 != Process.myUid() / 100_000) throw SecurityException("Different Android user")
        val allowed = listOf("com.miui.home", "com.android.systemui").filter { pkg ->
            runCatching { app.packageManager.getApplicationInfo(pkg, 0).uid == uid }.getOrDefault(false)
        }
        if (allowed.isEmpty()) throw SecurityException("Diagnostics caller is outside the hooked scopes")
        val encoded = extras?.getString(HookDiagnostics.EXTRA_SNAPSHOT) ?: error("Missing snapshot")
        val snapshot = HookDiagnostics.decode(encoded)
        if (snapshot.packageName !in allowed || snapshot.pid != pid || pid <= 0 ||
            !(app.packageManager.getPackagesForUid(uid).orEmpty().contains(snapshot.packageName))) {
            throw SecurityException("Diagnostics caller does not own this scope/process")
        }
        require(snapshot.processName == snapshot.packageName || snapshot.processName.startsWith("${snapshot.packageName}:"))
        require(snapshot.startedElapsedMs > 0 && snapshot.capturedElapsedMs >= snapshot.startedElapsedMs)
        val identity = Binder.clearCallingIdentity()
        try {
            synchronized(snapshotLock) {
                val prefs = app.getSharedPreferences(HookDiagnostics.PREFS, Context.MODE_PRIVATE)
                val previous = runCatching { prefs.getString(snapshot.processName, null)?.let(HookDiagnostics::decode) }.getOrNull()
                // An older queued report cannot replace evidence from a newly started process.
                val bootUnknown = snapshot.systemBootId == null && previous?.systemBootId == null
                if (previous == null || previous.systemBootId != snapshot.systemBootId ||
                    (bootUnknown && snapshot.capturedAtMillis >= previous.capturedAtMillis) ||
                    snapshot.startedElapsedMs > previous.startedElapsedMs ||
                    (snapshot.pid == previous.pid && snapshot.startedElapsedMs == previous.startedElapsedMs &&
                        snapshot.capturedElapsedMs >= previous.capturedElapsedMs)) {
                    require(prefs.edit().putString(snapshot.processName, HookDiagnostics.encode(snapshot)).commit())
                }
            }
            return Bundle().apply { putBoolean("accepted", true) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
