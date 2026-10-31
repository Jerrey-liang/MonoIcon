package com.jerrey.monoicon.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOs4LargeIconGeometryTest {
    @Test fun nativeResponseRequiresCurrentNoncePidProfileAndFiniteScalars() {
        val digest = "a".repeat(64)
        val nonce = "b".repeat(32)
        val response = "MONOICON_GEOMETRY 1 123 $digest $nonce 100 120 100 0 0.64 0\n"
        assertEquals(metrics(), HyperOs4LargeIconGeometry.parseResponse(response, 123, digest, nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response, 124, digest, nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response, 123, "c".repeat(64), nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response, 123, digest, "d".repeat(32)))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response + "extra", 123, digest, nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response.replace("120", "NaN"), 123, digest, nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response.replace("120", "Infinity"), 123, digest, nonce))
        assertNull(HyperOs4LargeIconGeometry.parseResponse(response.replace("GEOMETRY 1", "GEOMETRY 2"), 123, digest, nonce))
    }

    @Test fun tabletsAndFoldableOuterDisplaysDoNotUseThePhoneFormula() {
        assertTrue(HyperOs4LargeIconGeometry.supportedPhone(393, "nosdcard", "1", "0"))
        assertTrue(HyperOs4LargeIconGeometry.supportedPhone(393, "default", "", ""))
        assertFalse(HyperOs4LargeIconGeometry.supportedPhone(700, "default", "1", "0"))
        assertFalse(HyperOs4LargeIconGeometry.supportedPhone(393, "tablet", "1", "0"))
        assertFalse(HyperOs4LargeIconGeometry.supportedPhone(393, "default", "3", "0"))
        assertFalse(HyperOs4LargeIconGeometry.supportedPhone(393, "default", "1", "2"))
        assertFalse(HyperOs4LargeIconGeometry.supportedPhone(393, "default", "invalid", "0"))
    }

    @Test fun largeTilesUseTheLauncherGridAndStaySquareAtTwoByTwo() {
        val geometry = metrics()!!
        assertEquals(HyperOs4LargeIconGeometry.Size(64.0, 64.0), geometry.dimensions(1, 1))
        assertEquals(HyperOs4LargeIconGeometry.Size(184.0, 64.0), geometry.dimensions(2, 1))
        assertEquals(HyperOs4LargeIconGeometry.Size(84.0, 184.0), geometry.dimensions(1, 2))
        assertEquals(HyperOs4LargeIconGeometry.Size(184.0, 184.0), geometry.dimensions(2, 2))
        assertNull(geometry.dimensions(3, 1))
    }

    @Test fun providerWidthSelectsLauncherOrientation() {
        val geometry = HyperOs4LargeIconGeometry.fromProviderMetrics(
            130.0, 140.0, 100.0, 130.0, 0.64, 0.5,
        )!!
        assertEquals(65.0, geometry.normalIconSize, 1e-9)
        assertEquals(HyperOs4LargeIconGeometry.Size(205.0, 65.0), geometry.dimensions(2, 1))
    }

    @Test fun ambiguousOrientationAndInvalidProviderResultsAreRejected() {
        assertNull(HyperOs4LargeIconGeometry.fromProviderMetrics(100.0, 120.0, 100.0, 100.0, 0.6, 0.7))
        assertNull(HyperOs4LargeIconGeometry.fromProviderMetrics(100.0, 120.0, 90.0, 130.0, 0.6, 0.7))
        assertNull(metrics(width = Double.NaN))
        assertNull(metrics(height = Double.POSITIVE_INFINITY))
        assertNull(metrics(scale = 0.0))
        assertNull(metrics(width = 100.0, height = 10.0, scale = 0.5))
    }

    @Test fun equalOrientationScalesCanShareGeometryAndSignatureTracksLayout() {
        val same = HyperOs4LargeIconGeometry.fromProviderMetrics(100.0, 120.0, 100.0, 100.0, 0.64, 0.64)!!
        assertEquals(metrics()!!.signature, same.signature)
        assertNotEquals(metrics()!!.signature, metrics(height = 121.0)!!.signature)
        assertNotEquals(metrics()!!.signature, metrics(scale = 0.65)!!.signature)
    }

    private fun metrics(width: Double = 100.0, height: Double = 120.0, scale: Double = 0.64) =
        HyperOs4LargeIconGeometry.fromProviderMetrics(width, height, width, Double.NaN, scale, Double.NaN)
}
