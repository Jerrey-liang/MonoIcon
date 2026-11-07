package com.jerrey.monoicon.profile

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMatchTest {
    private val name = "RELEASE-8.01.02.6144-260818-08251107-R"
    private val match = ProfileMatch(
        versionCodes = setOf(801026144L),
        versionNames = setOf(name),
        sdkMin = 37,
        sdkMax = 37,
        properties = mapOf("ro.build.version.incremental" to listOf("OS4.")),
    )
    private val environment = ProfileEnvironment(
        versionCode = 801026144L,
        versionName = name,
        sdk = 37,
        arm64 = true,
        hasCode = false,
        hasSplits = false,
        properties = mapOf("ro.build.version.incremental" to "OS4.0.0.3.XAACNXM"),
    )

    @Test fun matchingNativeEnvironmentPasses() {
        assertNull(match.mismatch(environment))
    }

    @Test fun versionCodeAndVersionNameMustBothMatchExactly() {
        assertNotNull(match.mismatch(environment.copy(versionCode = 801028565L)))
        assertNotNull(match.mismatch(environment.copy(versionName = "$name-extra")))
        assertNotNull(match.mismatch(environment.copy(versionName = name.lowercase())))
        assertNotNull(match.mismatch(environment.copy(versionName = "")))
    }

    @Test fun compatibilityComesFromProfileDataInsteadOfA6144Constant() {
        val custom = match.copy(versionCodes = setOf(9_000_000_001L), versionNames = setOf("custom-build"))
        assertNull(custom.mismatch(environment.copy(versionCode = 9_000_000_001L, versionName = "custom-build")))
        assertNotNull(custom.mismatch(environment))
    }

    @Test fun sdkBoundsAreInclusiveAndOutsideVersionsAreRejected() {
        val range = match.copy(sdkMin = 37, sdkMax = 39)
        assertNull(range.mismatch(environment.copy(sdk = 37)))
        assertNull(range.mismatch(environment.copy(sdk = 39)))
        assertNotNull(range.mismatch(environment.copy(sdk = 36)))
        assertNotNull(range.mismatch(environment.copy(sdk = 40)))
    }

    @Test fun matchingMetadataCannotDivertJavaOrUnsupportedApkShapes() {
        assertTrue(match.mismatch(environment.copy(arm64 = false))!!.contains("arm64"))
        assertTrue(match.mismatch(environment.copy(hasCode = true))!!.contains("Java"))
        assertTrue(match.mismatch(environment.copy(hasSplits = true))!!.contains("Split"))
    }

    @Test fun missingOrDifferentSystemPropertiesReportTheActualConstraint() {
        val missing = match.mismatch(environment.copy(properties = emptyMap()))
        val wrong = match.mismatch(environment.copy(properties = mapOf("ro.build.version.incremental" to "OS3.0")))
        assertTrue(missing!!.contains("ro.build.version.incremental="))
        assertTrue(wrong!!.contains("OS3.0"))
        assertNotNull(match.mismatch(environment.copy(properties = mapOf("ro.build.version.incremental" to "os4.0"))))
    }

    @Test fun eachPropertyMustMatchOneOfItsOwnAllowedPrefixes() {
        val multiple = match.copy(properties = mapOf(
            "ro.build.version.incremental" to listOf("OS4.0.", "OS4.1."),
            "ro.product.device" to listOf("rubens", "test-device"),
        ))
        val matching = environment.copy(properties = mapOf(
            "ro.build.version.incremental" to "OS4.1.2.0",
            "ro.product.device" to "rubens",
        ))
        assertNull(multiple.mismatch(matching))
        assertNotNull(multiple.mismatch(matching.copy(properties = matching.properties - "ro.product.device")))
        assertNotNull(multiple.mismatch(matching.copy(properties = matching.properties + ("ro.product.device" to "another"))))
    }

    @Test fun declaredSystemVersionsAreExactEvenWhenLauncherAndPrefixMatch() {
        val declared = match.copy(systemVersions = setOf("OS4.0.0.3.XAACNXM", "OS4.0.0.4.XAACNXM"))
        assertNull(declared.mismatch(environment))
        assertNull(declared.mismatch(environment.copy(properties = mapOf("ro.build.version.incremental" to "OS4.0.0.4.XAACNXM"))))
        for (version in listOf("OS4.0.0.5.XAACNXM", "OS4.0.0.3.XAACNXM-extra", "os4.0.0.3.XAACNXM", "")) {
            assertNotNull(declared.mismatch(environment.copy(properties = mapOf("ro.build.version.incremental" to version))))
        }
        assertNotNull(declared.mismatch(environment.copy(properties = emptyMap())))
    }
}
