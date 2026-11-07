package com.jerrey.monoicon.safety

import org.junit.Assert.*
import org.junit.Test

class LauncherSafetyStateTest {
    private val boot = "12345678-1234-1234-1234-123456789abc"
    private fun record(blocked: Int, reset: String = "-") = "MONOICON_SAFE 1 $blocked $boot 4321 12345 120000 0 $reset\n"

    @Test fun persistentBlockIsReleasedOnlyByExplicitRetryReceipt() {
        assertTrue(LauncherSafetyState.isBlocked(record(1), "-"))
        val token = "0123456789abcdef0123456789abcdef"
        assertFalse(LauncherSafetyState.isBlocked(record(1), token))
        assertTrue(LauncherSafetyState.isBlocked(record(1, token), token))
        assertTrue(LauncherSafetyState.isBlocked(record(1, token), "-"))
    }

    @Test fun absenceIsSafeButCorruptionCannotSilentlyEnable() {
        assertFalse(LauncherSafetyState.isBlocked(null, "-"))
        assertTrue(LauncherSafetyState.isBlocked("", "-"))
        assertTrue(LauncherSafetyState.isBlocked(record(0) + "extra", "-"))
        assertTrue(LauncherSafetyState.isBlocked(record(0), "broken reset"))
        assertTrue(LauncherSafetyState.isBlocked(record(0).replace("4321", "-1"), "-"))
    }
}
