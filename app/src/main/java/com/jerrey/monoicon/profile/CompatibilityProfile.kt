package com.jerrey.monoicon.profile

/** Version data is owned by Profile; palette choices never influence matching. */
internal data class ProfileMatch(
    val versionCodes: Set<Long>,
    val versionNames: Set<String>,
    val sdkMin: Int,
    val sdkMax: Int,
    val properties: Map<String, List<String>>,
    /** Exact ro.build.version.incremental values verified by the Profile author. */
    val systemVersions: Set<String>? = null,
) {
    fun mismatch(environment: ProfileEnvironment): String? = when {
        !environment.arm64 -> "arm64-v8a is required"
        environment.hasCode -> "Launcher uses the Java pipeline"
        environment.hasSplits -> "Split launcher APKs are not supported by this native driver"
        environment.versionCode !in versionCodes -> "Launcher versionCode ${environment.versionCode} does not match"
        environment.versionName !in versionNames -> "Launcher versionName ${environment.versionName} does not match"
        environment.sdk !in sdkMin..sdkMax -> "Android SDK ${environment.sdk} is outside $sdkMin..$sdkMax"
        systemVersions != null && environment.properties[SYSTEM_VERSION_PROPERTY] !in systemVersions ->
            "System version ${environment.properties[SYSTEM_VERSION_PROPERTY].orEmpty()} does not match ${systemVersions.joinToString()}"
        else -> properties.entries.firstOrNull { (name, prefixes) ->
            val actual = environment.properties[name].orEmpty()
            prefixes.none(actual::startsWith)
        }?.let { "System property ${it.key}=${environment.properties[it.key].orEmpty()} does not match" }
    }

    companion object {
        const val SYSTEM_VERSION_PROPERTY = "ro.build.version.incremental"
    }
}

internal data class ProfileEnvironment(
    val versionCode: Long,
    val versionName: String,
    val sdk: Int,
    val arm64: Boolean,
    val hasCode: Boolean,
    val hasSplits: Boolean,
    val properties: Map<String, String>,
)

internal data class ProfileImage(val library: String, val size: Long, val sha256: String)
internal data class ProfileRefresh(val action: String, val packageExtra: String)

internal data class ProfileIconRules(
    val names: List<String>,
    val aliases: Map<String, String>,
    val largeIconPrefixes: List<String> = emptyList(),
    val geometryBridge: String? = null,
) {
    fun componentNames(pkg: String, cls: String): List<String> = names.mapNotNull {
        when (it) {
            "class" -> "$cls.png"
            "component" -> "$pkg#$cls.png"
            "qualified" -> if (cls.startsWith(pkg)) "$cls.png" else "$pkg#$cls.png"
            else -> null // Package fallback is assigned to the primary launch activity.
        }
    }.distinct().filter(::safeIconName)

    companion object {
        private val safe = Regex("[A-Za-z0-9_.$#-]+\\.png")
        fun safeIconName(name: String) = name.length <= 255 && ".." !in name && safe.matches(name)
    }
}

internal data class CompatibilityProfile(
    val id: String,
    val name: String,
    val revision: Int,
    val official: Boolean,
    val source: ByteArray,
    val digest: String,
    val match: ProfileMatch,
    val images: List<ProfileImage>,
    val icons: ProfileIconRules,
    val settingsKey: String,
    val refresh: ProfileRefresh? = null,
)

internal object ProfileSelection {
    /** An explicit choice never silently falls back to another profile. */
    fun choose(matches: List<CompatibilityProfile>, selected: String?, approvedDigests: Set<String> = emptySet()): CompatibilityProfile? =
        if (selected != null) matches.singleOrNull {
            it.id == selected && (it.match.systemVersions != null || it.digest in approvedDigests)
        } else matches.filter { it.match.systemVersions != null }.singleOrNull()
}
