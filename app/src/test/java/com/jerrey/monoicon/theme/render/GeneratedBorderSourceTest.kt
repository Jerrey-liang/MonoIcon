package com.jerrey.monoicon.theme.render

import com.jerrey.monoicon.theme.mask.MaskStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedBorderSourceTest {
    @Test fun everyGeneratedSourceCanAdjustItsBorder() {
        for (source in listOf(
            MaskStrategy.SOURCE_FOREGROUND,
            MaskStrategy.SOURCE_LUMINANCE,
            MaskStrategy.SOURCE_AOSP_ADAPTIVE,
            MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE,
            MaskStrategy.SOURCE_AOSP_LEGACY,
        )) {
            assertTrue("generated source $source supports border adjustment", adjustsGeneratedBorder(source))
        }
    }

    @Test fun nativeAndLawniconsArtworkKeepTheirAuthoredGeometry() {
        assertFalse(adjustsGeneratedBorder(MaskStrategy.SOURCE_NATIVE))
        assertFalse(adjustsGeneratedBorder(MaskStrategy.SOURCE_LAWNICONS))
    }

    @Test fun unknownSourcesDoNotEnableArtworkZoom() {
        for (source in listOf(-1, 0, Int.MAX_VALUE)) assertFalse(adjustsGeneratedBorder(source))
    }
}
