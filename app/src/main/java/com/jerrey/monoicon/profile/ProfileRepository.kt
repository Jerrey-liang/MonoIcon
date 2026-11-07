package com.jerrey.monoicon.profile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** App-private catalog and read-only preflight. Call IO methods off the UI thread. */
internal object ProfileRepository {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val REVOKED_DIGEST = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val ASSETS = "hyperos4/profiles"
    private const val PACKAGE = "com.miui.home"
    private var cachedKey: String? = null
    private var cachedResolution: Resolution? = null

    data class Catalog(val profiles: List<CompatibilityProfile>, val errors: List<String>)
    data class Resolution(
        val profile: CompatibilityProfile?,
        val launcher: ApplicationInfo?,
        val environment: String,
        val reason: String,
        val candidates: Map<String, String> = emptyMap(),
        /** Compatible legacy profiles which still need confirmation for this build. */
        val manualApprovalRequired: Set<String> = emptySet(),
    )

    fun selected(context: Context): String? = context.getSharedPreferences("hyperos4-profiles", Context.MODE_PRIVATE)
        .getString("selected", null)

    @Synchronized fun select(context: Context, id: String?, acknowledgeUnverifiedSystem: Boolean = false, expectedDigest: String? = null) {
        val profile = id?.let { requested -> catalog(context).profiles.singleOrNull { it.id == requested } }
        require(id == null || profile != null) { "Profile no longer exists" }
        require(expectedDigest == null || profile?.digest == expectedDigest) { "Profile changed; review it again before enabling" }
        val preferences = context.getSharedPreferences("hyperos4-profiles", Context.MODE_PRIVATE)
        val editor = preferences.edit().putString("selected", id)
        if (profile != null && profile.match.systemVersions == null) {
            val approval = approvalValue(profile)
            require(acknowledgeUnverifiedSystem || preferences.getString("approval.$id", null) == approval) {
                "Profile does not declare verified system versions; manual confirmation is required"
            }
            editor.putString("approval.$id", approval)
        }
        check(editor.commit())
        invalidate()
    }

    /** Also published beside a legacy native Profile so a stale commit cannot survive an OTA. */
    fun systemApprovalToken(): String = systemApprovalToken(
        ProfileNative.systemProperty("ro.build.fingerprint"),
        ProfileNative.systemProperty(ProfileMatch.SYSTEM_VERSION_PROPERTY),
        Build.VERSION.SDK_INT,
    )

    internal fun systemApprovalToken(fingerprint: String, incremental: String, sdk: Int): String {
        require(fingerprint.isNotEmpty() && incremental.isNotEmpty() && sdk > 0) { "System identity is unavailable" }
        return sha256("$fingerprint\n$incremental\n$sdk".toByteArray(Charsets.UTF_8))
    }

    private fun approvalValue(profile: CompatibilityProfile) = "${profile.digest}:${systemApprovalToken()}"

    @Synchronized fun invalidate() { cachedKey = null; cachedResolution = null }

    private fun directory(context: Context) = File(context.filesDir, "hyperos4-profiles").apply { check(mkdirs() || isDirectory) }

    fun readBounded(input: InputStream): ByteArray = input.use {
        val bytes = it.readNBytes(MAX_BYTES + 1)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Profile must be between 1 byte and 2 MiB" }
        bytes
    }

    fun parse(source: ByteArray, official: Boolean = false): CompatibilityProfile {
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
        val aliases = icons.getJSONObject("aliases")
        return CompatibilityProfile(
            root.getString("id"), root.getString("name"), number(root.get("revision")).toInt(), official, source.copyOf(), sha256(source),
            ProfileMatch(match.getJSONArray("versionCodes").strings().map(String::toLong).toSet(),
                match.getJSONArray("versionNames").strings().toSet(), number(match.get("sdkMin")).toInt(), number(match.get("sdkMax")).toInt(), properties,
                match.optJSONArray("systemVersions")?.strings()?.toSet()),
            root.getJSONArray("images").objects().map { ProfileImage(it.getString("library"), number(it.get("size")), it.getString("sha256")) },
            ProfileIconRules(icons.getJSONArray("names").strings(), aliases.keys().asSequence().associateWith(aliases::getString),
                icons.optJSONArray("largeIconPrefixes")?.strings().orEmpty(),
                icons.optString("geometryBridge").takeIf(String::isNotEmpty)),
            root.getString("settingsKey"),
            root.optJSONObject("refresh")?.let { ProfileRefresh(it.getString("action"), it.getString("packageExtra")) },
        )
    }

    @Synchronized fun catalog(context: Context): Catalog {
        val profiles = mutableListOf<CompatibilityProfile>()
        val errors = mutableListOf<String>()
        context.assets.list(ASSETS).orEmpty().filter { it.endsWith(".json") }.sorted().forEach { name ->
            runCatching { parse(readBounded(context.assets.open("$ASSETS/$name")), true) }
                .onSuccess { profiles += it }.onFailure { errors += "$name: ${it.message}" }
        }
        directory(context).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { file ->
            runCatching {
                val p = parse(readBounded(file.inputStream()))
                require(file.name == "${p.id}.json" && profiles.none { it.id == p.id }) { "Duplicate or inconsistent Profile id" }
                p
            }.onSuccess { profiles += it }.onFailure { errors += "${file.name}: ${it.message}" }
        }
        return Catalog(profiles, errors)
    }

    @Synchronized fun import(context: Context, source: ByteArray): CompatibilityProfile {
        val p = parse(source)
        val catalog = catalog(context)
        require(catalog.profiles.none { it.official && it.id == p.id }) { "Official id is reserved; give your custom Profile a new id" }
        require(catalog.profiles.count { !it.official } < 64 || catalog.profiles.any { it.id == p.id }) { "At most 64 imported Profiles are stored" }
        val target = AtomicFile(File(directory(context), "${p.id}.json"))
        val stream = target.startWrite()
        try { stream.write(source); target.finishWrite(stream) }
        catch (failure: Throwable) { target.failWrite(stream); throw failure }
        // Importing/replacing a legacy file is never an implicit approval.
        check(context.getSharedPreferences("hyperos4-profiles", Context.MODE_PRIVATE).edit().remove("approval.${p.id}").commit())
        invalidate()
        return p
    }

    @Synchronized fun delete(context: Context, id: String) {
        val p = catalog(context).profiles.single { it.id == id }
        require(!p.official) { "Official Profiles cannot be deleted" }
        check(File(directory(context), "$id.json").delete())
        check(context.getSharedPreferences("hyperos4-profiles", Context.MODE_PRIVATE).edit().remove("approval.$id").commit())
        if (selected(context) == id) select(context, null)
        invalidate()
    }

    fun nativeLauncher(context: Context): ApplicationInfo? = runCatching {
        context.packageManager.getApplicationInfo(PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            .takeIf { it.flags and ApplicationInfo.FLAG_HAS_CODE == 0 && "arm64-v8a" in Build.SUPPORTED_ABIS }
    }.getOrNull()

    @Synchronized fun resolve(context: Context): Resolution {
        val catalog = catalog(context)
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0)) }.getOrNull()
            ?: return Resolution(null, null, "", "Launcher package is not installed")
        val launcher = info.applicationInfo ?: return Resolution(null, null, "", "Launcher metadata unavailable")
        val properties = (catalog.profiles.flatMap { it.match.properties.keys } + ProfileMatch.SYSTEM_VERSION_PROPERTY)
            .distinct().associateWith(ProfileNative::systemProperty)
        val env = ProfileEnvironment(info.longVersionCode, info.versionName.orEmpty(), Build.VERSION.SDK_INT,
            "arm64-v8a" in Build.SUPPORTED_ABIS, launcher.flags and ApplicationInfo.FLAG_HAS_CODE != 0, !launcher.splitSourceDirs.isNullOrEmpty(), properties)
        val description = "${env.versionName} (${env.versionCode}) · SDK ${env.sdk}\n" + properties.entries.joinToString("\n") { "${it.key}=${it.value}" }
        val selection = selected(context)
        val preferences = context.getSharedPreferences("hyperos4-profiles", Context.MODE_PRIVATE)
        val systemToken = runCatching(::systemApprovalToken).getOrNull()
        val approvedDigests = catalog.profiles.filter { p ->
            systemToken != null && preferences.getString("approval.${p.id}", null) == "${p.digest}:$systemToken"
        }.map { it.digest }.toSet()
        val key = "${info.lastUpdateTime}|${launcher.sourceDir}|${launcher.nativeLibraryDir}|$env|$selection|$systemToken|$approvedDigests|${catalog.profiles.map { it.digest }}"
        if (key == cachedKey) return checkNotNull(cachedResolution)
        val reasons = mutableMapOf<String, String>()
        val matches = catalog.profiles.filter { p ->
            val mismatch = p.match.mismatch(env) ?: verifyBinaries(launcher, p)
            reasons[p.id] = mismatch ?: "Compatible"
            mismatch == null
        }
        val manualApprovalRequired = matches.filter { it.match.systemVersions == null && it.digest !in approvedDigests }.map { it.id }.toSet()
        manualApprovalRequired.forEach { reasons[it] = "System compatibility is unverified; enable this Profile manually after confirmation" }
        val profile = ProfileSelection.choose(matches, selection, approvedDigests)
        val reason = when {
            profile != null -> "Compatible: ${profile.name} · r${profile.revision}"
            selection != null -> reasons[selection] ?: "Selected Profile is missing or invalid"
            matches.count { it.match.systemVersions != null } > 1 -> "Multiple compatible Profiles; choose one explicitly"
            env.hasCode -> "Launcher uses the existing Java hooks"
            else -> "No compatible Profile\n" + reasons.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
        return Resolution(profile, launcher, description, (listOf(reason) + catalog.errors).joinToString("\n"), reasons, manualApprovalRequired).also {
            cachedKey = key; cachedResolution = it
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
