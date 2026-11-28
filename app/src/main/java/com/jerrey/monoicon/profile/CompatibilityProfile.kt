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
internal data class CompatibilityProfile(
    val digest: String,
    val match: ProfileMatch,
    val images: List<ProfileImage>,
    val largeIconPrefixes: List<String>,
)
