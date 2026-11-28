package com.jerrey.monoicon.profile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact built-in compatibility check for the optional large-icon bridge only. */
internal object ProfileRepository {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val ASSETS = "hyperos4/profiles"
    private const val PACKAGE = "com.miui.home"
    private var cachedKey: String? = null
    private var cachedResolution: Resolution? = null

    data class Resolution(
        val profile: CompatibilityProfile?,
        val launcher: ApplicationInfo?,
    )

    private fun readBounded(input: InputStream): ByteArray = input.use {
        val bytes = it.readNBytes(MAX_BYTES + 1)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Profile must be between 1 byte and 2 MiB" }
        bytes
    }

    private fun parse(source: ByteArray): CompatibilityProfile {
        require(source.size <= MAX_BYTES) { "Profile exceeds 2 MiB" }
        val error = ProfileNative.validate(source)
        require(error.isEmpty()) { error }
        // Native validation rejects duplicate keys, invalid UTF-8 and coercions
        // before JSONObject projects the already-validated metadata for the UI.
        val root = JSONObject(source.toString(Charsets.UTF_8))
        val match = root.getJSONObject("match")
        val properties = match.getJSONArray("properties").objects().associate {
            it.getString("name") to it.getJSONArray("prefixes").strings()
        }
        val icons = root.getJSONObject("icons")
        return CompatibilityProfile(
            sha256(source),
            ProfileMatch(match.getJSONArray("versionCodes").strings().map(String::toLong).toSet(),
                match.getJSONArray("versionNames").strings().toSet(), number(match.get("sdkMin")).toInt(), number(match.get("sdkMax")).toInt(), properties,
                match.getJSONArray("systemVersions").strings().toSet()),
            root.getJSONArray("images").objects().map { ProfileImage(it.getString("library"), number(it.get("size")), it.getString("sha256")) },
            icons.getJSONArray("largeIconPrefixes").strings(),
        )
    }

    @Synchronized fun resolve(context: Context): Resolution {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
            ?: return Resolution(null, null)
        val launcher = info.applicationInfo ?: return Resolution(null, null)
        val key = "${info.lastUpdateTime}|${launcher.sourceDir}|${Build.VERSION.INCREMENTAL}"
        if (key == cachedKey) return checkNotNull(cachedResolution)
        val profile = context.assets.open("$ASSETS/hyperos4-6144.json").use { parse(readBounded(it)) }
        val properties = (profile.match.properties.keys + ProfileMatch.SYSTEM_VERSION_PROPERTY)
            .associateWith(ProfileNative::systemProperty)
        val env = ProfileEnvironment(info.longVersionCode, info.versionName.orEmpty(), Build.VERSION.SDK_INT,
            "arm64-v8a" in Build.SUPPORTED_ABIS, launcher.flags and ApplicationInfo.FLAG_HAS_CODE != 0, !launcher.splitSourceDirs.isNullOrEmpty(), properties)
        val mismatch = profile.match.mismatch(env) ?: verifyBinaries(launcher, profile)
        return Resolution(profile.takeIf { mismatch == null }, launcher).also {
            cachedKey = key
            cachedResolution = it
        }
    }

    private fun verifyBinaries(launcher: ApplicationInfo, profile: CompatibilityProfile): String? = try {
        ZipFile(launcher.sourceDir).use { zip ->
            if (zip.entries().asSequence().any { Regex("classes(?:[0-9]+)?\\.dex").matches(it.name) }) return "Launcher contains DEX"
            for (image in profile.images) {
                val entry = zip.getEntry("lib/arm64-v8a/${image.library}")
                val extracted = File(launcher.nativeLibraryDir.orEmpty(), image.library)
                val size = entry?.size ?: extracted.takeIf(File::isFile)?.length() ?: return "Missing ELF ${image.library}"
                if (size != image.size) return "ELF size mismatch: ${image.library}"
                val stream = if (entry != null) zip.getInputStream(entry) else extracted.inputStream()
                val digest = stream.use { hashStream(it, image.size) }
                if (digest != image.sha256) return "ELF SHA-256 mismatch: ${image.library}"
            }
            null
        }
    } catch (e: Exception) { "Cannot inspect launcher ELF: ${e.message}" }

    private fun hashStream(stream: InputStream, expectedSize: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(65536); var total = 0L
        while (true) { val count = stream.read(block); if (count < 0) break; total += count; require(total <= expectedSize); digest.update(block, 0, count) }
        require(total == expectedSize)
        return digest.digest().hex()
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun JSONArray.strings() = (0 until length()).map(::getString)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun number(value: Any): Long = value.toString().let { if (it.startsWith("0x")) it.drop(2).toLong(16) else it.toLong() }
}
