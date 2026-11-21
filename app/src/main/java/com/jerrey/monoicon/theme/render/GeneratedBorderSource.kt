package com.jerrey.monoicon.theme.render

import com.jerrey.monoicon.theme.mask.MaskStrategy

/** Authored monochrome layers keep their intended plate and glyph geometry. */
internal fun adjustsGeneratedBorder(source: Int): Boolean = when (source) {
    MaskStrategy.SOURCE_FOREGROUND,
    MaskStrategy.SOURCE_LUMINANCE,
    MaskStrategy.SOURCE_AOSP_ADAPTIVE,
    MaskStrategy.SOURCE_AOSP_ADAPTIVE_SILHOUETTE,
    MaskStrategy.SOURCE_AOSP_LEGACY -> true
    else -> false
}
