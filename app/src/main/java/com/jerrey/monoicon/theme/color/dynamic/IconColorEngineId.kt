package com.jerrey.monoicon.theme.color.dynamic

import com.jerrey.monoicon.material2025.dynamiccolor.ColorSpec

/** Stable preference IDs for the independently selectable icon color specification. */
enum class IconColorEngineId(val id: String, val label: String, internal val specVersion: ColorSpec.SpecVersion) {
    MATERIAL_2025("material_2025", "Material 2025", ColorSpec.SpecVersion.SPEC_2025),
    MATERIAL_3("material_3", "Material 3 (MD3)", ColorSpec.SpecVersion.SPEC_2021);

    companion object {
        val DEFAULT = MATERIAL_2025

        fun fromId(id: String?): IconColorEngineId = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
