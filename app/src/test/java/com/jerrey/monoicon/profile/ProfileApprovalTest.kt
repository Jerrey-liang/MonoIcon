package com.jerrey.monoicon.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProfileApprovalTest {
    @Test fun approvalBindsTheFullSystemIdentity() {
        val fingerprint = "xiaomi/device/product:17/BUILD/OS4.0.0.3:user/release-keys"
        val incremental = "OS4.0.0.3.XAACNXM"
        val token = ProfileRepository.systemApprovalToken(fingerprint, incremental, 37)
        assertEquals(ProfileRepository.sha256("$fingerprint\n$incremental\n37".toByteArray()), token)
        assertEquals(64, token.length)
        assertNotEquals(token, ProfileRepository.systemApprovalToken("$fingerprint-new", incremental, 37))
        assertNotEquals(token, ProfileRepository.systemApprovalToken(fingerprint, "OS4.0.0.4.XAACNXM", 37))
        assertNotEquals(token, ProfileRepository.systemApprovalToken(fingerprint, incremental, 38))
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingFingerprintCannotBeApproved() {
        ProfileRepository.systemApprovalToken("", "OS4.0.0.3.XAACNXM", 37)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingSystemVersionCannotBeApproved() {
        ProfileRepository.systemApprovalToken("fingerprint", "", 37)
    }
}
