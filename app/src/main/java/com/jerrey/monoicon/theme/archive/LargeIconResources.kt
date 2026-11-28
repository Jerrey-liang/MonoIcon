package com.jerrey.monoicon.theme.archive

import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.HexFormat

/** Static resources installed by Xiaomi Themes for the primary user's desktop. */
internal object LargeIconResources {
    const val DESKTOP = "/data/system/theme/large_icons/0/apps/desktop"
    const val CONFIG = "runtime_largeicon.config"
    const val MAX_PNG = 8L * 1024 * 1024
    const val MAX_FILES = 4096
    private val packageName = Regex("""[a-zA-Z_][a-zA-Z_0-9]*(?:\.[a-zA-Z_][a-zA-Z_0-9]*)+""")
    private val drawable = Regex("""drawable(?:-sw720dp)?(?:-(?:ldpi|mdpi|hdpi|xhdpi|nxhdpi|xxhdpi|xxxhdpi|nodpi))?""")
    private val spans = setOf("1x2", "2x1", "2x2")

    data class Asset(val path: String, val width: Int, val height: Int, val sha: String) {
        val pkg get() = path.substringBefore('/')
    }

    fun validPath(relative: String): Boolean {
        val parts = relative.split('/')
        return parts.size == 5 && parts[0].length <= 251 && packageName.matches(parts[0]) &&
            parts[1] in spans && parts[2] == "res" && drawable.matches(parts[3]) && parts[4] == parts[0] + ".png"
    }

    fun checkedFile(root: File, relative: String): File? {
        require(validPath(relative))
        val file = File(root, relative)
        return file.takeIf { it.canonicalFile == it.absoluteFile && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
    }

    fun digest(file: File): String {
        require(file.length() in 1..MAX_PNG)
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val size = input.read(buffer)
                if (size == -1) break
                total += size
                require(total <= MAX_PNG)
                hash.update(buffer, 0, size)
            }
        }
        return HexFormat.of().formatHex(hash.digest())
    }

    fun dimensions(file: File): Pair<Int, Int>? {
        if (!ThemeIconZip.isRenderedPng(file)) return null
        return DataInputStream(file.inputStream()).use { input ->
            input.skipNBytes(16)
            val width = input.readInt()
            val height = input.readInt()
            (width to height).takeIf { width in 1..2048 && height in 1..2048 }
        }
    }

    fun scan(root: File): List<Asset> {
        val config = File(root, CONFIG)
        if (Files.notExists(config.toPath(), NOFOLLOW_LINKS)) return emptyList()
        check(config.canonicalFile == config.absoluteFile && Files.isRegularFile(config.toPath(), NOFOLLOW_LINKS))
        require(config.length() in 2..512L * 1024)
        val json = JSONObject(config.readText())
        val result = mutableListOf<Asset>()
        var totalBytes = 0L
        for (key in json.keys()) {
            val item = json.optJSONObject(key) ?: continue
            val pkg = item.optString("packageName")
            if (!packageName.matches(pkg) || pkg.length > 251 || item.optBoolean("isPairApp")) continue
            val app = File(root, pkg)
            if ("/" + item.optString("path").trim('/') != app.absolutePath || app.canonicalFile != app.absoluteFile) continue
            for (span in spans) {
                val res = File(app, "$span/res")
                if (res.canonicalFile != res.absoluteFile || !res.isDirectory) continue
                // MAML animations and layered resources have their own loader and artwork.
                val spanDir = res.parentFile!!
                if (listOf("manifest.xml", "fancy_icons", "animating_icons", "layer_animating_icons", "pair").any {
                        File(spanDir, it).exists() || File(res, it).exists()
                    }) continue
                val buckets = res.listFiles() ?: throw IOException("Cannot read ${res.name}")
                for (bucket in buckets.filter { drawable.matches(it.name) }) {
                    val path = "$pkg/$span/res/${bucket.name}/$pkg.png"
                    val file = checkedFile(root, path) ?: continue
                    val (width, height) = dimensions(file) ?: continue
                    totalBytes += file.length()
                    require(result.size < MAX_FILES && totalBytes <= ThemeIconZip.MAX_ARCHIVE)
                    result += Asset(path, width, height, digest(file))
                }
            }
        }
        return result.distinctBy { it.path }.sortedBy { it.path }
    }
}
