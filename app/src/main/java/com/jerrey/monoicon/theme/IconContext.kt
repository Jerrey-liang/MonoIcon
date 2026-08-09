package com.jerrey.monoicon.theme

/**
 * Rendering context for icon generation (Phase 5).
 *
 * Desktop and folder previews use different mask source priorities
 * (verified in Phase 3.17: applying NATIVE_FIRST to folders changes
 * results for apps with native monochrome layers).
 */
enum class IconContext(val label: String) {
    /** Desktop / workspace icons (ShortcutIcon.setIconDrawable). */
    DESKTOP("desktop"),

    /** Folder preview icons (FolderPreviewIconView.refreshIconDrawable). */
    FOLDER_PREVIEW("folder"),
}
