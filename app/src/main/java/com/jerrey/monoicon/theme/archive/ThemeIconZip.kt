package com.jerrey.monoicon.theme.archive

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A patch contains complete replacements for the listed packages. Only exact indexed names
 * belong to MonoIcon. In particular, com.example.pro never belongs to com.example. */
internal object ThemeIconZip {
    private const val INDEX = "META-INF/monoicon-theme-v1.tsv"
    private const val SHADOW = "META-INF/monoicon-theme-alternates/"
    private const val DRAWABLES = "res/drawable-xxhdpi/"
    internal const val MAX_ARCHIVE = 384L * 1024 * 1024
    private const val MAX_EXPANDED = 768L * 1024 * 1024
    private const val MAX_FILES = 32768
    private const val MAX_PNG = 8L * 1024 * 1024
    private const val MAX_INDEX = 6L * 1024 * 1024
    private val packagePattern = Regex("[a-zA-Z_][a-zA-Z_0-9]*(\\.[a-zA-Z_][a-zA-Z_0-9]*)*")
    private val digestPattern = Regex("[a-f0-9]{64}")
    private val pngHeader = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    private data class Resource(val pkg: String, val name: String)
    private data class Index(val packages: Set<String>, val hashes: Map<Resource, String>)

    // HyperOS has one visible filename for an activity com.a.b and the package com.a.b.
    // Prefer the most specific package and retain the other PNG so uninstall can reveal it.
    private fun storageNames(resources: Set<Resource>): Map<Resource, String> = buildMap {
        for ((name, owners) in resources.groupBy { it.name }) {
            val visible = owners.maxBy { it.pkg.length }
            for (owner in owners) {
                put(owner, if (owner == visible) name else SHADOW + owner.pkg + "/" + name.removePrefix(DRAWABLES))
            }
        }
    }

    enum class Change { NONE, INSTALL, RESTORE }

    /** Cheap cache check for the PNG header, dimensions and complete IEND chunk. */
    fun isRenderedPng(file: File): Boolean {
        if (!file.isFile || file.length() !in 57..MAX_PNG) return false
        return try {
            RandomAccessFile(file, "r").use { input ->
                val signature = ByteArray(pngHeader.size).also(input::readFully)
                if (!signature.contentEquals(pngHeader) || input.readInt() != 13 || input.readInt() != 0x49484452 ||
                    input.readInt() !in 1..4096 || input.readInt() !in 1..4096) return@use false
                input.seek(input.length() - 12)
                input.readInt() == 0 && input.readInt() == 0x49454e44 && input.readInt() == 0xae426082.toInt()
            }
        } catch (_: IOException) {
            false
        }
    }

    fun writePatch(file: File, packages: Map<String, Map<String, File>>) {
        val files = buildMap {
            for ((pkg, images) in packages) {
                validatePackage(pkg)
                for ((name, image) in images) {
                    validateName(pkg, name)
                    put(Resource(pkg, name), image)
                }
            }
        }
        require(files.size < MAX_FILES)
        val names = storageNames(files.keys)
        val entries = mutableMapOf<Resource, String>()
        var total = 0L
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(1)
            for ((resource, image) in files) {
                val size = image.length()
                total += size
                require(total <= MAX_ARCHIVE && isRenderedPng(image)) { "Invalid rendered PNG" }
                val sha = MessageDigest.getInstance("SHA-256")
                image.inputStream().buffered().use { input ->
                    zip.putNextEntry(ZipEntry(names.getValue(resource)))
                    transfer(input, zip, size, sha = sha)
                    zip.closeEntry()
                }
                entries[resource] = hex(sha.digest())
            }
            writeIndex(zip, Index(packages.keys, entries))
        }
        require(file.length() <= MAX_ARCHIVE)
    }

    fun isManaged(file: File): Boolean =
        file.isFile && ZipFile(file).use { it.getEntry(INDEX) != null }

    fun merge(original: File?, active: File?, patch: File, full: Boolean, output: File): Change =
        ZipFile(patch).use { updates ->
            val incoming = readIndex(updates)
            active?.let(::ZipFile).use { previous ->
                val old = previous?.let(::readIndex)?.hashes.orEmpty()
                val retained = if (full) emptyMap() else old.filterKeys { it.pkg !in incoming.packages }
                val wanted = retained + incoming.hashes
                when {
                    wanted == old -> Change.NONE
                    wanted.isEmpty() -> Change.RESTORE
                    else -> {
                        val names = storageNames(wanted.keys)
                        val covered = names.values.toSet()
                        val incomingNames = storageNames(incoming.hashes.keys)
                        val oldNames = storageNames(old.keys)
                        ZipOutputStream(output.outputStream().buffered()).use { zip ->
                            zip.setLevel(1)
                            original?.let(::ZipFile).use { base ->
                                if (base != null) {
                                    validateBase(base)
                                    base.comment?.let(zip::setComment)
                                    for (entry in base.entries()) {
                                        if (entry.name !in covered) copyEntry(base, entry, zip)
                                    }
                                }
                                if (base?.getEntry("transform_config.xml") == null) {
                                    zip.putNextEntry(ZipEntry("transform_config.xml"))
                                    zip.write("<IconTransform/>".toByteArray())
                                    zip.closeEntry()
                                }
                            }
                            for ((resource, hash) in wanted) {
                                val isNew = resource in incoming.hashes
                                val source = if (isNew) updates else checkNotNull(previous)
                                val entry = source.getEntry((if (isNew) incomingNames else oldNames).getValue(resource))
                                copyEntry(source, requireNotNull(entry), zip, names.getValue(resource), hash)
                            }
                            writeIndex(zip, Index(wanted.keys.map { it.pkg }.toSet(), wanted))
                        }
                        require(output.length() <= MAX_ARCHIVE) { "Theme archive exceeds size limit" }
                        Change.INSTALL
                    }
                }
            }
        }

    private fun validateBase(zip: ZipFile) {
        require(zip.getEntry(INDEX) == null) { "Original theme already has MonoIcon metadata" }
        require(zip.size() <= MAX_FILES)
        val names = HashSet<String>()
        var expanded = 0L
        for (entry in zip.entries()) {
            val name = entry.name
            require(name.isNotEmpty() && name.length <= 1024 && names.add(name))
            require(!name.startsWith(SHADOW)) { "Original theme contains reserved MonoIcon resources" }
            require(!name.startsWith('/') && '\\' !in name && name.none { it.code < 32 } &&
                name.removeSuffix("/").split('/').none { it == ".." || it == "." || it.isEmpty() })
            require(entry.size in 0..MAX_ARCHIVE && entry.compressedSize in 0..MAX_ARCHIVE)
            expanded += entry.size
            require(expanded <= MAX_EXPANDED)
            val upper = name.uppercase(java.util.Locale.ROOT)
            require(!(upper.startsWith("META-INF/") &&
                listOf(".RSA", ".DSA", ".EC", ".SF").any(upper::endsWith))) { "Signed theme archive" }
        }
    }

    private fun writeIndex(zip: ZipOutputStream, index: Index) {
        val text = buildString {
            for (pkg in index.packages.sorted()) append(pkg).append('\n')
            for ((resource, hash) in index.hashes) {
                append(resource.pkg).append('\t').append(resource.name).append('\t').append(hash).append('\n')
            }
        }.toByteArray()
        require(text.size <= MAX_INDEX)
        zip.putNextEntry(ZipEntry(INDEX))
        zip.write(text)
        zip.closeEntry()
    }

    private fun readIndex(zip: ZipFile): Index {
        require(zip.size() <= 2 * MAX_FILES + 2)
        val index = requireNotNull(zip.getEntry(INDEX)) { "Missing MonoIcon theme metadata" }
        require(index.size in 0..MAX_INDEX)
        val packages = HashSet<String>()
        val entries = LinkedHashMap<Resource, String>()
        val body = java.io.ByteArrayOutputStream()
        zip.getInputStream(index).use { transfer(it, body, index.size) }
        for (line in body.toString(Charsets.UTF_8.name()).lineSequence().filter(String::isNotEmpty)) {
            val fields = line.split('\t')
            val pkg = fields.first()
            validatePackage(pkg)
            if (fields.size == 1) {
                require(packages.add(pkg))
            } else {
                require(fields.size == 3 && pkg in packages && digestPattern.matches(fields[2]))
                val name = fields[1]
                validateName(pkg, name)
                require(entries.size < MAX_FILES && entries.put(Resource(pkg, name), fields[2]) == null)
            }
        }
        for (name in storageNames(entries.keys).values) {
            require(zip.getEntry(name)?.size in pngHeader.size.toLong()..MAX_PNG)
        }
        return Index(packages, entries)
    }

    private fun validatePackage(pkg: String) {
        require(pkg.length in 1..255 && packagePattern.matches(pkg)) { "Invalid theme package name" }
    }

    private fun validateName(pkg: String, name: String) {
        require(name.length <= 1024 && name.startsWith(DRAWABLES) && name.endsWith(".png"))
        val stem = name.removePrefix(DRAWABLES).removeSuffix(".png")
        if (stem == pkg) return
        val cls = if (stem.startsWith("$pkg#")) stem.substringAfter('#') else stem
        require(stem.startsWith("$pkg#") || stem.startsWith("$pkg."))
        require(cls.split('.').all { part ->
            part.isNotEmpty() && Character.isJavaIdentifierStart(part.first()) &&
                part.all { Character.isJavaIdentifierPart(it) && !Character.isIdentifierIgnorable(it) }
        }) { "Invalid theme component name" }
    }

    private fun copyEntry(from: ZipFile, entry: ZipEntry, to: ZipOutputStream, name: String = entry.name,
                          expectedHash: String? = null) {
        val copy = if (name == entry.name) ZipEntry(entry) else ZipEntry(name)
        if (copy.method == ZipEntry.DEFLATED) copy.compressedSize = -1
        to.putNextEntry(copy)
        val crc = CRC32()
        val sha = expectedHash?.let { MessageDigest.getInstance("SHA-256") }
        from.getInputStream(entry).use { transfer(it, to, entry.size, crc, sha) }
        if (crc.value != entry.crc || (sha != null && hex(sha.digest()) != expectedHash)) {
            throw IOException("Theme entry checksum mismatch")
        }
        to.closeEntry()
    }

    private fun transfer(input: InputStream, output: OutputStream, size: Long, crc: CRC32? = null,
                         sha: MessageDigest? = null) {
        val buffer = ByteArray(16 * 1024)
        var remaining = size
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("Truncated theme resource")
            output.write(buffer, 0, count)
            crc?.update(buffer, 0, count)
            sha?.update(buffer, 0, count)
            remaining -= count
        }
        if (input.read() != -1) throw IOException("Oversized theme resource")
    }

    internal fun hex(bytes: ByteArray): String = buildString(2 * bytes.size) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            append("0123456789abcdef"[value ushr 4])
            append("0123456789abcdef"[value and 15])
        }
    }
}
