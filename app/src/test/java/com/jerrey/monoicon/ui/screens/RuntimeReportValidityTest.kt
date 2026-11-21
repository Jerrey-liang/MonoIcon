package com.jerrey.monoicon.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReportValidityTest {
    private val pid = 4321
    private val startTicks = 987_654L
    private val boot = "11111111-2222-3333-4444-555555555555"
    private val nonce = "0123456789abcdef"

    @Test
    fun matchingRequestAndProcessLifetimeIsCurrent() {
        assertTrue(currentNativeReport(
            pid = pid,
            startTicks = startTicks,
            reportBoot = boot,
            currentBoot = boot,
            reportNonce = nonce,
            requestedNonce = nonce,
            processes = mapOf(pid to startTicks),
        ))
    }

    @Test
    fun reusedPidIsNotCurrent() {
        assertFalse(currentNativeReport(
            pid, startTicks, boot, boot, nonce, nonce,
            processes = mapOf(pid to startTicks + 1),
        ))
    }

    @Test
    fun changedBootIsNotCurrent() {
        assertFalse(currentNativeReport(
            pid, startTicks, boot, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", nonce, nonce,
            processes = mapOf(pid to startTicks),
        ))
    }

    @Test
    fun mismatchedNonceIsNotCurrent() {
        assertFalse(currentNativeReport(
            pid, startTicks, boot, boot, nonce, "different-request",
            processes = mapOf(pid to startTicks),
        ))
    }

    @Test
    fun missingIdentityFieldsAreNotCurrent() {
        val missing = listOf(
            currentNativeReport(0, startTicks, boot, boot, nonce, nonce, mapOf(pid to startTicks)),
            currentNativeReport(pid, 0, boot, boot, nonce, nonce, mapOf(pid to startTicks)),
            currentNativeReport(pid, startTicks, "", boot, nonce, nonce, mapOf(pid to startTicks)),
            currentNativeReport(pid, startTicks, boot, "", nonce, nonce, mapOf(pid to startTicks)),
            currentNativeReport(pid, startTicks, boot, boot, "", nonce, mapOf(pid to startTicks)),
            currentNativeReport(pid, startTicks, boot, boot, nonce, "", mapOf(pid to startTicks)),
            currentNativeReport(pid, startTicks, boot, boot, nonce, nonce, emptyMap()),
        )
        missing.forEach { assertFalse(it) }
    }
}
