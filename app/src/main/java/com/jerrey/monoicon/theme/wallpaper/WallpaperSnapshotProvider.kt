package com.jerrey.monoicon.theme.wallpaper

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException

/** Read-only same-user bridge for the actual launcher/SystemUI UIDs, never a public wallpaper URI. */
class WallpaperSnapshotProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = context ?: throw FileNotFoundException("Wallpaper provider unavailable")
        val caller = Binder.getCallingUid()
        val verifiedPackages = app.packageManager.getPackagesForUid(caller)?.filter { name ->
            runCatching { app.packageManager.getApplicationInfo(name, 0).uid == caller }.getOrDefault(false)
        }?.toSet().orEmpty()
        if (!WallpaperImagePolicy.mayReadSnapshot(Process.myUid(), caller, verifiedPackages)) {
            throw SecurityException("Wallpaper snapshot is restricted to the module, launcher and SystemUI in this user")
        }
        require(mode == "r" && uri.authority == WallpaperSourceRepository.AUTHORITY && uri.path == "/home") {
            "Only the home snapshot may be read"
        }
        // Authorized callers wake the app even after its Activity has exited. This binder worker
        // waits for the app's serial image worker; root is never invoked under the hook's UID.
        val identity = Binder.clearCallingIdentity()
        try {
            WallpaperSourceRepository.refreshAndAwait(app, uri.getQueryParameter("force") == "1")
            val file = WallpaperSourceRepository.cacheFile(app)
            // Open the committed inode without AtomicFile.openRead's backup recovery mutation.
            // A concurrent atomic rename keeps this descriptor on one complete generation.
            if (!file.isFile) throw FileNotFoundException("Home wallpaper snapshot is not ready")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun getType(uri: Uri): String = "application/vnd.monoicon.wallpaper-snapshot"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
