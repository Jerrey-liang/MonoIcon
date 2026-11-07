package com.jerrey.monoicon.profile

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ProfileSelectionTest {
    private fun profile(id: String, official: Boolean = false) = CompatibilityProfile(
        id = id,
        name = id,
        revision = 1,
        official = official,
        source = byteArrayOf(),
        digest = "0".repeat(64),
        match = ProfileMatch(setOf(801026144L), setOf("test-build"), 37, 37,
            mapOf("ro.build.version.incremental" to listOf("OS4.")), setOf("OS4.0.0.3.XAACNXM")),
        images = listOf(ProfileImage("libapp.so", 1234L, "1".repeat(64))),
        icons = ProfileIconRules(listOf("qualified", "class", "package"), emptyMap()),
        settingsKey = "key_monochrome_color",
    )

    @Test fun automaticSelectionRequiresExactlyOneCompatibleProfile() {
        val only = profile("official", official = true)
        assertNull(ProfileSelection.choose(emptyList(), null))
        assertSame(only, ProfileSelection.choose(listOf(only), null))
        assertNull(ProfileSelection.choose(listOf(only, profile("custom")), null))
    }

    @Test fun explicitChoiceResolvesAmbiguityWithoutPreferringOfficialData() {
        val official = profile("official", official = true)
        val custom = profile("custom")
        assertSame(custom, ProfileSelection.choose(listOf(official, custom), "custom"))
        assertSame(official, ProfileSelection.choose(listOf(official, custom), "official"))
    }

    @Test fun unavailableExplicitChoiceNeverFallsBackToTheRemainingMatch() {
        val remaining = profile("other")
        assertNull(ProfileSelection.choose(listOf(remaining), "selected-but-incompatible"))
        assertNull(ProfileSelection.choose(emptyList(), "deleted-profile"))
        assertNull(ProfileSelection.choose(listOf(remaining), ""))
    }

    @Test fun duplicateSelectedIdsAreAmbiguous() {
        val official = profile("same", official = true)
        val custom = profile("same")
        assertNull(ProfileSelection.choose(listOf(official, custom), "same"))
        assertNull(ProfileSelection.choose(listOf(official, custom), null))
    }

    @Test fun legacyProfileRequiresBothExplicitSelectionAndApproval() {
        val original = profile("legacy")
        val legacy = original.copy(match = original.match.copy(systemVersions = null))
        assertNull(ProfileSelection.choose(listOf(legacy), null))
        assertNull(ProfileSelection.choose(listOf(legacy), legacy.id))
        assertNull(ProfileSelection.choose(listOf(legacy), null, setOf(legacy.digest)))
        assertSame(legacy, ProfileSelection.choose(listOf(legacy), legacy.id, setOf(legacy.digest)))
        assertNull(ProfileSelection.choose(listOf(legacy.copy(digest = "f".repeat(64))), legacy.id, setOf(legacy.digest)))
    }

    @Test fun legacyImportCannotMakeADeclaredAutomaticProfileAmbiguous() {
        val declared = profile("declared")
        val legacy = profile("legacy").let { it.copy(match = it.match.copy(systemVersions = null)) }
        assertSame(declared, ProfileSelection.choose(listOf(declared, legacy), null))
        // An unavailable explicit choice cannot silently select the declared profile.
        assertNull(ProfileSelection.choose(listOf(declared, legacy), legacy.id))
    }
}
