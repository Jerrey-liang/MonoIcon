package com.jerrey.monoicon.theme.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LawniconsResourceIndexTest {
    @Test
    fun filtersMappingsWithoutActualAssetsBeforeBuildingPackageDefaults() {
        val resources = LawniconsResourceIndex(
            mapOf("app.good/.Main" to "main", "app.missing" to "missing", "app.good" to "missing"),
            mapOf("app.alias/.Entry" to "alias", "app.badalias" to "missing"),
            setOf("main", "alias"),
        )
        assertEquals(setOf("app.good", "app.alias"), resources.packages)
        assertEquals("main", resources.resolve("app.good"))
        assertEquals("alias", resources.resolve("app.alias"))
        assertNull(resources.resolve("app.missing"))
        assertNull(resources.resolve("app.badalias"))
    }

    @Test
    fun componentOnlyFallbackIsDeterministicAndExplicitPackageEntryWins() {
        val components = linkedMapOf("app.pkg/.Zulu" to "zulu", "app.pkg/.Alpha" to "alpha")
        val resources = LawniconsResourceIndex(components, emptyMap())
        assertEquals("alpha", resources.resolve("app.pkg"))
        assertEquals("zulu", resources.resolve("app.pkg/app.pkg.Zulu"))
        assertEquals("alpha", LawniconsResourceIndex(components.toList().reversed().toMap(), emptyMap()).resolve("app.pkg"))
        assertEquals("package", LawniconsResourceIndex(components + ("app.pkg" to "package"), emptyMap()).resolve("app.pkg"))
    }

    @Test
    fun componentAliasesAndPackageAliasesUseRuntimePriority() {
        val resources = LawniconsResourceIndex(
            mapOf("app.pkg/.Main" to "main", "app.pkg/.Other" to "other"),
            mapOf("app.pkg/.Main" to "alias"),
        )
        assertEquals("alias", resources.resolve("app.pkg/.Main"))
        assertEquals("alias", resources.resolve("app.pkg/Main"))
        assertEquals("other", resources.resolve("app.pkg/.Other"))
        val packageAlias = LawniconsResourceIndex(resources.index, resources.aliases + ("app.pkg" to "package_alias"))
        assertEquals("alias", packageAlias.resolve("app.pkg/.Main"))
        assertEquals("package_alias", packageAlias.resolve("app.pkg/.Other"))
    }

    @Test
    fun malformedIdentitiesDoNotBecomeSupportedPackages() {
        val resources = LawniconsResourceIndex(
            mapOf("" to "empty", "unknown/.Main" to "unknown", "app.valid" to ""), emptyMap(),
        )
        assertEquals(emptySet<String>(), resources.packages)
        assertNull(resources.resolve(null))
        assertNull(resources.resolve("unknown"))
        assertNull(resources.resolve("/app.Main"))
    }
}
