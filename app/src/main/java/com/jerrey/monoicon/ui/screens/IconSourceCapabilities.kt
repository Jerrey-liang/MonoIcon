package com.jerrey.monoicon.ui.screens

import com.jerrey.monoicon.config.IconSourceStyle

/** Only authored sources present in this app can be selected explicitly. */
internal fun availableIconSourceStyles(app: LawniconsApp): List<IconSourceStyle> =
    IconSourceStyle.entries.filter { style ->
        when (style) {
            IconSourceStyle.NATIVE, IconSourceStyle.NATIVE_GENERATED -> app.hasNativeMonochrome
            IconSourceStyle.LAWNICONS -> app.hasLawnicons
            IconSourceStyle.GENERATED -> true
        }
    }

/** The inherited result already has its own option, so do not repeat it as a custom source. */
internal fun customIconSourceStyles(
    app: LawniconsApp,
    defaultSource: IconSourceStyle,
    allowLawniconsFallback: Boolean = true,
): List<IconSourceStyle> = availableIconSourceStyles(app).filterNot {
    resolvedAppIconSource(app, it, allowLawniconsFallback) == defaultSource
}

/** Mirrors mask selection when an older saved preference no longer has a resource. */
internal fun resolvedAppIconSource(
    app: LawniconsApp,
    preferred: IconSourceStyle,
    allowLawniconsFallback: Boolean,
): IconSourceStyle = when (preferred) {
    IconSourceStyle.GENERATED -> IconSourceStyle.GENERATED
    IconSourceStyle.NATIVE -> when {
        app.hasNativeMonochrome -> IconSourceStyle.NATIVE
        allowLawniconsFallback && app.hasLawnicons -> IconSourceStyle.LAWNICONS
        else -> IconSourceStyle.GENERATED
    }
    IconSourceStyle.NATIVE_GENERATED -> if (app.hasNativeMonochrome) IconSourceStyle.NATIVE else IconSourceStyle.GENERATED
    IconSourceStyle.LAWNICONS -> when {
        allowLawniconsFallback && app.hasLawnicons -> IconSourceStyle.LAWNICONS
        app.hasNativeMonochrome -> IconSourceStyle.NATIVE
        else -> IconSourceStyle.GENERATED
    }
}
