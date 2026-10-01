package com.jerrey.monoicon.logging

import android.content.Context
import android.net.Uri

/**
 * Writes the runtime log to a user-chosen document (Phase 14).
 *
 * The whole point of using the Storage Access Framework is that it needs no
 * permission at all: the user picks the destination in the system picker and
 * grants access to exactly that one file. Nothing here touches public storage,
 * and nothing here reads logcat — the bytes come from [LogStore], which is the
 * record MonoIcon produced itself.
 */
object LogExport {

    /** MIME type offered to the picker; the payload really is plain text. */
    const val MIME_TYPE = "text/plain"

    /** Timestamped name the picker pre-fills. */
    fun suggestedFileName(): String = LogStore.exportFileName()

    /**
     * Copies the current log into [uri].
     *
     * @return true when the document was written. A false result covers a
     *  revoked grant, a full volume, and any other write failure — the caller
     *  only needs to tell the user it did not work.
     */
    fun writeTo(context: Context, uri: Uri): Boolean = try {
        val payload = LogStore.exportText()
        // "wt" truncates: the picker may hand back a document that already
        // exists, and appending a second log to it would be misleading.
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        } ?: false
    } catch (t: Throwable) {
        LogStore.record(LogStore.LEVEL_ERROR, "MonoIcon.Log", "export failed: ${t.message}", t)
        false
    }
}
