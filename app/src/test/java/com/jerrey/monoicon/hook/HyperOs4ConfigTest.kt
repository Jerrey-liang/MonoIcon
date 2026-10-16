package com.jerrey.monoicon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOs4ConfigTest {
    @Test fun generationMakesAcknowledgementUnique() {
        val config = HyperOs4Config(true, -1, 0xff000000.toInt(), false, true)
        assertEquals("MONOICON 2 1 0xffffffff 0xff000000 0 1 g-0123456789abcdef0123456789abcdef\n",
            config.encode("g-0123456789abcdef0123456789abcdef"))
        assertFalse(config.encode("g-0123456789abcdef0123456789abcdef") ==
            config.encode("g-1123456789abcdef0123456789abcdef"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun generationCannotEscapeTheIconDirectory() {
        HyperOs4Config(true, -1, -1, false, true).encode("../other")
    }

    @Test fun transportPreservesUnsignedArgbAndPublishesDisabledState() {
        assertEquals(
            "MONOICON 1 0 0xff6750a4 0xff000000 1 0\n",
            HyperOs4Config(false, 0xff6750a4.toInt(), 0xff000000.toInt(), true, false).encode(),
        )
    }

    @Test fun onlyTheVerifiedNativeBuildCanPublish() {
        val name = "RELEASE-8.01.02.6144-260818-08251107-R"
        assertTrue(HyperOs4Config.supports(801026144L, name, false, true))
        assertFalse(HyperOs4Config.supports(801027726L, name, false, true))
        assertFalse(HyperOs4Config.supports(801026144L, "different", false, true))
        assertFalse(HyperOs4Config.supports(801026144L, name, true, true))
        assertFalse(HyperOs4Config.supports(801026144L, name, false, false))
    }
}
