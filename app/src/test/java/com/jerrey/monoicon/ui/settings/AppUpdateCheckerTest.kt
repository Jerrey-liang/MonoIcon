package com.jerrey.monoicon.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun releaseTagsCompareNumerically() {
        assertTrue(AppUpdateChecker.isNewerVersion("v2.10.0", "2.9.9"))
        assertTrue(AppUpdateChecker.isNewerVersion("v3.0.0", "2.99.99"))
        assertFalse(AppUpdateChecker.isNewerVersion("v2.9.0", "2.10.0"))
    }

    @Test
    fun equivalentOrMissingVersionsDoNotOfferAnUpdate() {
        assertFalse(AppUpdateChecker.isNewerVersion("v2.1.0", "2.1.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v2.1", "2.1.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("latest", "2.1.0"))
    }
}
