package com.jerrey.monoicon.theme.archive

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** The app supplies image bytes. Only the root-side catalog can select a live destination. */
internal object LargeIconPatch {
    private const val VERSION = 1

    fun describe(asset: LargeIconResources.Asset) = "${asset.path}\t${asset.width}\t${asset.height}\t${asset.sha}"

    fun parse(line: String): LargeIconResources.Asset {
        val fields = line.split('\t')
        require(fields.size == 4 && LargeIconResources.validPath(fields[0]) && fields[3].matches(Regex("[0-9a-f]{64}")))
        return LargeIconResources.Asset(fields[0], fields[1].toInt(), fields[2].toInt(), fields[3]).also {
            require(it.width in 1..2048 && it.height in 1..2048)
        }
    }

    private fun fingerprint(catalog: List<LargeIconResources.Asset>) = ThemeIconZip.hex(
        MessageDigest.getInstance("SHA-256").digest(catalog.joinToString("\n", transform = ::describe).toByteArray())
    )

    fun write(patch: File, catalog: List<LargeIconResources.Asset>, images: Map<String, File>) {
        DataOutputStream(patch.outputStream().buffered()).use { out ->
            out.writeInt(VERSION)
            out.writeUTF(fingerprint(catalog))
            out.writeInt(images.size)
            var total = 0L
            for ((path, file) in images) {
                val length = file.length()
                total += length
                require(length in 1..LargeIconResources.MAX_PNG && total <= ThemeIconZip.MAX_ARCHIVE)
                out.writeUTF(path)
                out.writeInt(length.toInt())
                file.inputStream().use { check(it.copyTo(out) == length) }
            }
        }
    }

    fun apply(input: InputStream, catalog: List<LargeIconResources.Asset>, store: LargeIconFileStore, work: File,
              beforeCommit: () -> Unit) {
        val source = DataInputStream(input.buffered())
        require(source.readInt() == VERSION)
        check(source.readUTF() == fingerprint(catalog)) { "Themes changed the large-icon resources during rendering" }
        val count = source.readInt()
        require(count in 0..catalog.size)
        val byPath = catalog.associateBy { it.path }
        val staged = mutableMapOf<LargeIconResources.Asset, File>()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        repeat(count) {
            val asset = checkNotNull(byPath[source.readUTF()]) { "Unknown large-icon resource" }
            require(asset !in staged)
            var remaining = source.readInt()
            total += remaining
            require(remaining.toLong() in 1..LargeIconResources.MAX_PNG && total <= ThemeIconZip.MAX_ARCHIVE)
            val image = File(work, it.toString())
            image.outputStream().buffered().use { out ->
                while (remaining > 0) {
                    val n = minOf(remaining, buffer.size)
                    source.readFully(buffer, 0, n)
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
            check(LargeIconResources.dimensions(image) == (asset.width to asset.height)) { "Large-icon dimensions changed" }
            staged[asset] = image
        }
        require(source.read() == -1)
        beforeCommit()
        for ((asset, image) in staged) {
            check(store.replace(asset.path, asset.sha, image) || LargeIconResources.digest(image) == asset.sha) {
                "Themes replaced ${asset.path} during installation"
            }
        }
        store.restoreExcept(staged.keys.mapTo(mutableSetOf()) { it.path })
    }
}
