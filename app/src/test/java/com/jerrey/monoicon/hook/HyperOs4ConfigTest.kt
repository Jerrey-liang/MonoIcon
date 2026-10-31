package com.jerrey.monoicon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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

    @Test fun profileDigestAndIconGenerationAreBoundToTheSameAcknowledgement() {
        val config = HyperOs4Config(true, 0xff6750a4.toInt(), 0xffffffff.toInt(), true, false)
        val generation = "g-0123456789abcdef0123456789abcdef"
        val digest = "0123456789abcdef".repeat(4)
        val encoded = config.encode(generation, digest)
        assertEquals("MONOICON 3 1 0xff6750a4 0xffffffff 1 0 $generation $digest\n", encoded)
        assertNotEquals(encoded, config.encode(generation, "1".repeat(64)))
        assertNotEquals(encoded, config.encode("g-1123456789abcdef0123456789abcdef", digest))
        assertTrue("Encoded commit must fit the native 256-byte palette buffer", encoded.toByteArray(Charsets.UTF_8).size < 256)
    }

    @Test fun profileCommitPreservesTheDisabledState() {
        val generation = "g-" + "0".repeat(32)
        val digest = "f".repeat(64)
        assertEquals("MONOICON 3 0 0x00000000 0x80000000 0 1 $generation $digest\n",
            HyperOs4Config(false, 0, Int.MIN_VALUE, false, true).encode(generation, digest))
    }

    @Test fun profileDigestRejectsMalformedAndEscapingReferences() {
        val config = HyperOs4Config(true, -1, -1, false, true)
        val generation = "g-" + "0".repeat(32)
        for (digest in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "g".repeat(64), "../" + "a".repeat(61), "a".repeat(64) + "\n",
                "a".repeat(63) + " ", "a".repeat(63) + "\u0000")) {
            assertThrows("Invalid profile reference: $digest", IllegalArgumentException::class.java) {
                config.encode(generation, digest)
            }
        }
    }

    @Test fun profileCommitStillValidatesTheIconGeneration() {
        val config = HyperOs4Config(true, -1, -1, false, true)
        for (generation in listOf("", "../other", "g-" + "a".repeat(31),
                "g-" + "a".repeat(33), "g-" + "A".repeat(32), "g-" + "a".repeat(32) + "\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                config.encode(generation, "0".repeat(64))
            }
        }
    }
}
