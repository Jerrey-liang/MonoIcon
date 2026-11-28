package com.jerrey.monoicon.theme.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

enum class ThemeArchiveStatus { INACTIVE, ACTIVE, CONFLICT }
data class ThemeArchiveResult(val changed: Boolean, val refreshSucceeded: Boolean)
class ThemeArchiveConflictException : IOException("The active icon theme changed outside MonoIcon")

/** All calls are serialized by the root helper's process-wide file lock. The write-ahead record
 * and original live outside the theme directory, which Theme Manager may replace as a unit. */
internal class ThemeArchiveStore(
    private val target: File,
    private val stateDir: File,
    private val preserveAttributes: (reference: File, staged: File) -> Unit,
    private val syncDirectory: (File) -> Unit,
    private val refresh: () -> Boolean,
) {
    private val journal = File(stateDir, "state-v1")
    private val original = File(stateDir, "original.icons")

    // A dash identifies a missing icons module, including on a stock theme.
    private data class Record(val original: String, val before: String, val after: String, val refresh: Boolean)
    private data class Snapshot(val live: String, val record: Record?, val status: ThemeArchiveStatus)

    fun status() = snapshot().status

    /** Called under the root lock, before this invocation creates its own patch. */
    fun cleanInterruptedWork() {
        val files = stateDir.listFiles() ?: throw IOException("Cannot inspect private theme workspace")
        var removed = false
        for (file in files) {
            if (TEMPORARY.matches(file.name) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) {
                Files.delete(file.toPath())
                removed = true
            }
        }
        if (removed) syncDirectory(stateDir)
    }

    fun canRecoverMissingOriginal(): Boolean = recoverable(snapshot())

    /** Used only after explicit user selection. A theme service may have deleted the managed
     * archive. An external replacement, including a concurrent create, must always win. */
    fun recoverMissingOriginal(): ThemeArchiveResult {
        val current = snapshot()
        if (current.status == ThemeArchiveStatus.INACTIVE && current.record?.refresh == true) {
            return finish(current.record, false)
        }
        if (!recoverable(current)) throw ThemeArchiveConflictException()
        return put(current, original, original)
    }

    private fun recoverable(current: Snapshot): Boolean {
        val record = current.record ?: return false
        return current.status == ThemeArchiveStatus.CONFLICT && current.live == ABSENT &&
            record.original != ABSENT && runCatching { fingerprint(original) == record.original }.getOrDefault(false)
    }

    /** Acknowledge an external theme selection without writing anything to its directory. */
    fun acceptCurrentTheme() {
        val current = snapshot()
        if (current.status != ThemeArchiveStatus.CONFLICT) return
        if (current.record == null || (current.live != ABSENT && ThemeIconZip.isManaged(target))) {
            throw ThemeArchiveConflictException()
        }
        // Keep one complete previous backup for recovery when explicitly starting a new theme.
        if (current.record.original != ABSENT && Files.isRegularFile(original.toPath(), NOFOLLOW_LINKS)) {
            val verified = fingerprint(original) == current.record.original
            val saved = File(stateDir, if (verified) "previous-original.icons" else "previous-damaged.icons")
            val temp = File.createTempFile("save-", ".tmp", stateDir)
            try {
                copySynced(original, temp)
                atomicReplace(temp, saved)
            } finally {
                temp.delete()
            }
        }
        if (fingerprint(target) != current.live) throw ThemeArchiveConflictException()
        clearRecord()
    }

    fun apply(patch: File, full: Boolean): ThemeArchiveResult {
        val current = snapshot()
        if (current.status == ThemeArchiveStatus.CONFLICT) throw ThemeArchiveConflictException()
        val active = current.record?.takeIf { current.status == ThemeArchiveStatus.ACTIVE }
        val baseline = if (active != null) verifyOriginal(active) else target.takeIf(File::exists)
        val candidate = File.createTempFile("merge-", ".zip", stateDir)
        try {
            return when (ThemeIconZip.merge(baseline, target.takeIf { active != null }, patch, full, candidate)) {
                ThemeIconZip.Change.NONE -> finish(current.record, false)
                ThemeIconZip.Change.RESTORE -> restore(current)
                ThemeIconZip.Change.INSTALL -> {
                    if (active == null && baseline != null) {
                        val backup = File.createTempFile("original-", ".tmp", stateDir)
                        try {
                            copySynced(baseline, backup)
                            if (fingerprint(backup) != current.live) throw ThemeArchiveConflictException()
                            preserveAttributes(baseline, backup)
                            FileOutputStream(backup, true).use { it.fd.sync() }
                            atomicReplace(backup, original)
                            syncDirectory(stateDir)
                        } finally {
                            backup.delete()
                        }
                    }
                    put(current, candidate)
                }
            }
        } finally {
            candidate.delete()
        }
    }

    fun restore(): ThemeArchiveResult = restore(snapshot())

    private fun restore(current: Snapshot): ThemeArchiveResult = when (current.status) {
        ThemeArchiveStatus.CONFLICT -> {
            // The user or Theme Manager may already have restored the exact starting point.
            // Acknowledge it on disable only; background apply must still respect that change.
            if (current.live != current.record?.original || fingerprint(target) != current.live) {
                throw ThemeArchiveConflictException()
            }
            clearRecord()
            ThemeArchiveResult(false, true)
        }
        ThemeArchiveStatus.INACTIVE -> finish(current.record, false)
        ThemeArchiveStatus.ACTIVE -> {
            val backup = verifyOriginal(checkNotNull(current.record))
            put(current, backup, backup)
        }
    }

    private fun verifyOriginal(record: Record): File? {
        if (record.original == ABSENT) return null
        if (fingerprint(original) != record.original) throw IOException("Original theme backup checksum mismatch")
        return original
    }

    private fun put(current: Snapshot, contents: File?, attributes: File? = null): ThemeArchiveResult {
        val parent = checkNotNull(target.parentFile)
        val desired = contents?.let(::fingerprint) ?: ABSENT
        // After an interrupted explicit recovery, absence must still be a conflict until the
        // original was actually linked into place.
        val before = if (current.status == ThemeArchiveStatus.CONFLICT) checkNotNull(current.record).before else current.live
        val record = Record(current.record?.original ?: current.live, before, desired,
            current.record?.refresh == true)
        // Theme Manager scans the live directory. Only the completed, labelled inode belongs
        // there. link/ATOMIC_MOVE fail without copying if the directories span mounts.
        val stage = contents?.let { File.createTempFile("stage-", ".tmp", stateDir) }
        try {
            if (stage != null) {
                copySynced(checkNotNull(contents), stage)
                preserveAttributes(attributes ?: (if (current.live == ABSENT) parent else target), stage)
                FileOutputStream(stage, true).use { it.fd.sync() }
            }
            if (fingerprint(target) != current.live) throw ThemeArchiveConflictException()
            writeRecord(record)
            // Theme Manager doesn't use our lock. Check again immediately before publication.
            if (fingerprint(target) != current.live) throw ThemeArchiveConflictException()
            when {
                stage == null -> Files.delete(target.toPath())
                current.live == ABSENT -> {
                    try {
                        Files.createLink(target.toPath(), stage.toPath())
                    } catch (_: java.nio.file.FileAlreadyExistsException) {
                        throw ThemeArchiveConflictException()
                    }
                }
                else -> atomicReplace(stage, target)
            }
            syncDirectory(parent)
            if (stage != null) {
                // link() retained the private name; rename() already removed it. Persist the
                // source directory too before marking this cross-directory commit complete.
                Files.deleteIfExists(stage.toPath())
                syncDirectory(stateDir)
            }
            val committed = record.copy(before = desired, refresh = true)
            writeRecord(committed)
            return finish(committed, true)
        } finally {
            stage?.delete()
        }
    }

    private fun finish(record: Record?, changed: Boolean): ThemeArchiveResult {
        if (record == null) return ThemeArchiveResult(changed, true)
        if (record.refresh) {
            val notified = refresh()
            // Theme services may reject or replace a module while handling its notification.
            if (fingerprint(target) != record.after) throw ThemeArchiveConflictException()
            if (!notified) return ThemeArchiveResult(changed, false)
        }
        if (record.after == record.original) {
            clearRecord()
        } else if (record.refresh) {
            writeRecord(record.copy(refresh = false))
        }
        return ThemeArchiveResult(changed, true)
    }

    private fun snapshot(): Snapshot {
        val live = fingerprint(target)
        if (!Files.exists(journal.toPath(), NOFOLLOW_LINKS)) {
            val unmanaged = live == ABSENT || !ThemeIconZip.isManaged(target)
            return Snapshot(live, null, if (unmanaged) ThemeArchiveStatus.INACTIVE else ThemeArchiveStatus.CONFLICT)
        }
        require(Files.isRegularFile(journal.toPath(), NOFOLLOW_LINKS) && journal.length() in 1..256)
        val fields = journal.readText().trimEnd().split('\n')
        require(fields.size == 4 && fields.take(3).all { it == ABSENT || HASH.matches(it) } &&
            fields.last() in setOf("0", "1")) { "Invalid MonoIcon theme journal" }
        var record = Record(fields[0], fields[1], fields[2], fields[3] == "1")
        if (live != record.before && live != record.after) {
            return Snapshot(live, record, ThemeArchiveStatus.CONFLICT)
        }
        if (record.before != record.after) {
            record = record.copy(before = live, after = live, refresh = live == record.after || record.refresh)
            writeRecord(record)
        }
        if (live == record.original && !record.refresh) {
            clearRecord()
            return Snapshot(live, null, ThemeArchiveStatus.INACTIVE)
        }
        return Snapshot(live, record,
            if (live == record.original) ThemeArchiveStatus.INACTIVE else ThemeArchiveStatus.ACTIVE)
    }

    private fun clearRecord() {
        Files.deleteIfExists(journal.toPath())
        syncDirectory(stateDir)
        Files.deleteIfExists(original.toPath())
        syncDirectory(stateDir)
    }

    private fun writeRecord(record: Record) {
        val temp = File.createTempFile("state-", ".tmp", stateDir)
        try {
            FileOutputStream(temp).use { out ->
                val flag = if (record.refresh) "1" else "0"
                out.write(listOf(record.original, record.before, record.after, flag).joinToString("\n").toByteArray())
                out.fd.sync()
            }
            atomicReplace(temp, journal)
            syncDirectory(stateDir)
        } finally {
            temp.delete()
        }
    }

    private fun fingerprint(file: File): String {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return ABSENT
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= ThemeIconZip.MAX_ARCHIVE) {
            "Invalid theme file"
        }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                total += count
                require(total <= ThemeIconZip.MAX_ARCHIVE)
                sha.update(buffer, 0, count)
            }
        }
        return ThemeIconZip.hex(sha.digest())
    }

    private fun copySynced(source: File, dest: File) {
        FileOutputStream(dest).use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    total += count
                    require(total <= ThemeIconZip.MAX_ARCHIVE)
                    out.write(buffer, 0, count)
                }
            }
            out.fd.sync()
        }
    }

    private fun atomicReplace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private companion object {
        const val ABSENT = "-"
        val HASH = Regex("[0-9a-f]{64}")
        val TEMPORARY = Regex("""(?:(?:stage|original|state|save)-[A-Za-z0-9_-]+\.tmp|(?:merge|patch)-[A-Za-z0-9_-]+\.zip|broadcast-[A-Za-z0-9_-]+\.txt)""")
    }
}
