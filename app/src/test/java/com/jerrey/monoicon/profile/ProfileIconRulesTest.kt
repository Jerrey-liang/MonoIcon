package com.jerrey.monoicon.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIconRulesTest {
    @Test fun officialNamesPreservePrimaryAndSharedActivityResolution() {
        val rules = ProfileIconRules(listOf("qualified", "class", "package"), emptyMap())
        assertEquals(listOf("com.example.MainActivity.png"),
            rules.componentNames("com.example", "com.example.MainActivity"))
        assertEquals(listOf("com.example#shared.MainActivity.png", "shared.MainActivity.png"),
            rules.componentNames("com.example", "shared.MainActivity"))
    }

    @Test fun customNamingOrderIsUsedAndDuplicateNamesAreCollapsed() {
        val rules = ProfileIconRules(listOf("class", "component", "qualified"), emptyMap())
        assertEquals(listOf("shared.Main.png", "com.example#shared.Main.png"),
            rules.componentNames("com.example", "shared.Main"))
        assertEquals(listOf("com.example.Main.png", "com.example#com.example.Main.png"),
            rules.componentNames("com.example", "com.example.Main"))
    }

    @Test fun packageFallbackIsNotAssignedToEveryComponent() {
        val rules = ProfileIconRules(listOf("package", "component"), emptyMap())
        assertEquals(listOf("com.example#com.example.Secondary.png"),
            rules.componentNames("com.example", "com.example.Secondary"))
    }

    @Test fun safeNamesAllowLauncherSeparatorsButRejectPathsAndNonPngFiles() {
        assertTrue(ProfileIconRules.safeIconName("com.example#shared.Main\$Nested.png"))
        assertTrue(ProfileIconRules.safeIconName("app_name-2.png"))
        for (name in listOf("", ".png", "../other.png", "com..example.png", "/other.png", "dir/other.png",
                "dir\\other.png", "other.PNG", "other.jpg", "other.png\n", "other\u0000.png")) {
            assertFalse("Unsafe icon name: $name", ProfileIconRules.safeIconName(name))
        }
    }

    @Test fun filesystemBasenameLimitAlsoAppliesToAliasesAndGeneratedNames() {
        val longest = "a".repeat(251) + ".png"
        assertTrue(ProfileIconRules.safeIconName(longest))
        assertFalse(ProfileIconRules.safeIconName("a$longest"))
        val rules = ProfileIconRules(listOf("component"), emptyMap())
        assertTrue(rules.componentNames("p", "a".repeat(250)).isEmpty())
    }

    @Test fun aliasesNeedSafeSourceAndDestinationNames() {
        val alias = "com.miui.weather2.png" to "com.miui.weather.png"
        assertTrue(ProfileIconRules.safeIconName(alias.first))
        assertTrue(ProfileIconRules.safeIconName(alias.second))
        assertFalse(ProfileIconRules.safeIconName("../${alias.second}"))
        assertFalse(ProfileIconRules.safeIconName("${alias.first}/child.png"))
    }
}
