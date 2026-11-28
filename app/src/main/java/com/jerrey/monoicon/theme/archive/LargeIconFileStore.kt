package com.jerrey.monoicon.theme.archive

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

/** The caller holds the root archive lock for all operations. */
internal class LargeIconFileStore(
    private val root: File,
    private val state: File,
    private val preserve: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
) {
    private data class Original(val relative: String, val digest: String)

    private val pending = File(state, "refresh-pending")

    fun replace(relative: String, expectedSha: String, rendered: File): Boolean {
        require(LargeIconResources.validPath(relative) && HASH.matches(expectedSha))
        val live = LargeIconResources.checkedFile(root, relative) ?: return false
        if (liveDigest(live) != expectedSha) return false
        require(Files.isRegularFile(rendered.toPath(), NOFOLLOW_LINKS))
        val desired = digest(rendered)
        if (desired == expectedSha) return false

        ensureDirectory(state)
        val entry = File(state, entryName(relative))
        ensureDirectory(entry)
        val original = readOriginal(entry)
        check(original == null || original.relative == relative)
        val held = temporary(entry)
        try {
            Files.delete(held.toPath())
            try {
                Files.createLink(held.toPath(), live.toPath())
            } catch (_: NoSuchFileException) {
                return false
            }
            if (unchanged(relative, held, expectedSha) == null) return false

            val owned = original?.let { ownedFile(entry, held, expectedSha) }
            if (owned == null) {
                prune(entry, setOf(held))
                saveOriginal(entry, relative, expectedSha, held)
            } else {
                verifyOriginal(entry, checkNotNull(original))
                prune(entry, setOf(held, owned, File(entry, RECORD), File(entry, ORIGINAL)))
            }

            val stage = temporary(entry)
            try {
                copy(stage, rendered, held, desired)
                val replacement = File(entry, anchorName(desired))
                // Keep both installed inode identities until publication completes. An
                // interrupted update can leave either one in the live resource tree.
                Files.createLink(replacement.toPath(), stage.toPath())
                syncDirectory(entry)
                return publish(relative, held, expectedSha, stage)
            } finally {
                Files.deleteIfExists(stage.toPath())
            }
        } finally {
            Files.deleteIfExists(held.toPath())
        }
    }

    fun restoreExcept(active: Set<String>): Boolean {
        if (!Files.exists(state.toPath(), NOFOLLOW_LINKS)) return false
        checkDirectory(state)
        var changed = false
        for (entry in children(state).filter { ENTRY.matches(it.name) }) {
            checkDirectory(entry)
            val original = readOriginal(entry)
            val live = original?.let { LargeIconResources.checkedFile(root, it.relative) }
            val actual = live?.let(::liveDigest)
            val owned = if (live == null || actual == null) null else ownedFile(entry, live, actual)
            if (original == null || owned == null || actual == null) {
                discard(entry)
                continue
            }
            prune(entry, setOf(owned, File(entry, RECORD), File(entry, ORIGINAL)))
            if (original.relative in active) continue

            val backup = verifyOriginal(entry, original)
            val stage = temporary(entry)
            try {
                copy(stage, backup, backup, original.digest)
                if (publish(original.relative, owned, actual, stage)) {
                    changed = true
                    discard(entry)
                }
            } finally {
                Files.deleteIfExists(stage.toPath())
            }
        }
        return changed
    }

    fun needsRefresh(): Boolean = Files.isRegularFile(pending.toPath(), NOFOLLOW_LINKS)

    fun markRefreshed() {
        if (Files.deleteIfExists(pending.toPath())) syncDirectory(state)
    }

    private fun publish(relative: String, held: File, expected: String, stage: File): Boolean {
        if (!needsRefresh()) {
            Files.createFile(pending.toPath())
            syncDirectory(state)
        }
        val live = unchanged(relative, held, expected) ?: return false
        Files.move(stage.toPath(), live.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(checkNotNull(live.parentFile))
        syncDirectory(checkNotNull(stage.parentFile))
        return true
    }

    private fun unchanged(relative: String, held: File, expected: String): File? {
        if (!Files.isRegularFile(held.toPath(), NOFOLLOW_LINKS) || liveDigest(held) != expected) return null
        val live = LargeIconResources.checkedFile(root, relative) ?: return null
        return live.takeIf { sameFile(it, held) }
    }

    private fun ownedFile(entry: File, live: File, digest: String): File? =
        File(entry, anchorName(digest)).takeIf { sameFile(live, it) }

    private fun sameFile(a: File, b: File): Boolean = try {
        Files.isRegularFile(a.toPath(), NOFOLLOW_LINKS) && Files.isRegularFile(b.toPath(), NOFOLLOW_LINKS) &&
            Files.isSameFile(a.toPath(), b.toPath())
    } catch (_: NoSuchFileException) {
        false
    }

    private fun saveOriginal(entry: File, relative: String, expected: String, source: File) {
        val backup = temporary(entry)
        val record = temporary(entry)
        try {
            copy(backup, source, source, expected)
            Files.move(backup.toPath(), File(entry, ORIGINAL).toPath(), ATOMIC_MOVE)
            FileOutputStream(record).use {
                it.write("$relative\n$expected".toByteArray())
                it.fd.sync()
            }
            Files.move(record.toPath(), File(entry, RECORD).toPath(), ATOMIC_MOVE)
            syncDirectory(entry)
        } finally {
            Files.deleteIfExists(backup.toPath())
            Files.deleteIfExists(record.toPath())
        }
    }

    private fun readOriginal(entry: File): Original? {
        val record = File(entry, RECORD)
        if (!Files.exists(record.toPath(), NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(record.toPath(), NOFOLLOW_LINKS) && record.length() in 1..1024)
        val fields = record.readLines()
        check(fields.size == 2 && LargeIconResources.validPath(fields[0]) && HASH.matches(fields[1]) &&
            entry.name == entryName(fields[0])) { "Invalid large-icon backup record" }
        return Original(fields[0], fields[1])
    }

    private fun verifyOriginal(entry: File, original: Original): File {
        val backup = File(entry, ORIGINAL)
        check(Files.isRegularFile(backup.toPath(), NOFOLLOW_LINKS) && digest(backup) == original.digest) {
            "Original large-icon backup checksum mismatch"
        }
        return backup
    }

    private fun copy(dest: File, source: File, attributes: File, expected: String) {
        FileOutputStream(dest).use { out ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= LargeIconResources.MAX_PNG) { "Large-icon PNG exceeds the size limit" }
                    out.write(buffer, 0, count)
                }
            }
            out.fd.sync()
        }
        if (digest(dest) != expected) throw IOException("Large-icon contents changed while staging")
        preserve(attributes, dest)
        FileOutputStream(dest, true).use { it.fd.sync() }
    }

    private fun discard(entry: File) {
        prune(entry, emptySet())
        Files.delete(entry.toPath())
        syncDirectory(state)
    }

    private fun prune(entry: File, keep: Set<File>) {
        val obsolete = children(entry).filterNot(keep::contains)
        if (obsolete.isEmpty()) return
        for (file in obsolete) Files.delete(file.toPath())
        syncDirectory(entry)
    }

    private fun children(dir: File): Array<File> = dir.listFiles()
        ?: throw IOException("Cannot inspect private large-icon workspace")

    private fun ensureDirectory(dir: File) {
        if (!Files.exists(dir.toPath(), NOFOLLOW_LINKS)) {
            Files.createDirectory(dir.toPath())
            syncDirectory(checkNotNull(dir.parentFile))
        }
        checkDirectory(dir)
    }

    private fun checkDirectory(dir: File) {
        check(dir.canonicalFile == dir.absoluteFile && Files.isDirectory(dir.toPath(), NOFOLLOW_LINKS))
    }

    private fun temporary(dir: File) = File.createTempFile("work-", ".tmp", dir)

    private fun digest(file: File) = LargeIconResources.digest(file)

    private fun liveDigest(file: File): String? =
        if (file.length() in 1..LargeIconResources.MAX_PNG) digest(file) else null

    private fun entryName(relative: String) = "entry-" +
        ThemeIconZip.hex(MessageDigest.getInstance("SHA-256").digest(relative.toByteArray()))

    private fun anchorName(digest: String) = "owned-$digest.png"

    private companion object {
        const val ORIGINAL = "original.png"
        const val RECORD = "original-v1"
        val HASH = Regex("[0-9a-f]{64}")
        val ENTRY = Regex("entry-[0-9a-f]{64}")
    }
}
