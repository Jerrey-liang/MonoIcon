package com.jerrey.monoicon.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOs4EnvironmentTest {
    @Test fun recognizesFirmwareAcrossLauncherBuilds() {
        assertTrue(HyperOs4Environment.isVersion4("OS4.0.0.3.XAACNXM", ""))
        assertTrue(HyperOs4Environment.isVersion4("vendor.incremental", "OS4.1"))
        assertTrue(HyperOs4Environment.isVersion4("", "4"))
        assertFalse(HyperOs4Environment.isVersion4("OS3.0.309", "OS4.0"))
        assertFalse(HyperOs4Environment.isVersion4("OS40.0", ""))
        assertFalse(HyperOs4Environment.isVersion4("", ""))
    }
}
