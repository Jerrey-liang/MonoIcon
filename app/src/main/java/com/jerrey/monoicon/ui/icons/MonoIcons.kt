package com.jerrey.monoicon.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The same rounded, filled Material icon family used by KernelSU's Miuix bar. */
internal object MonoIcons {
    val Home: ImageVector get() = Icons.Rounded.Cottage
    val IconStyle: ImageVector get() = Icons.Rounded.Category
    val Configuration: ImageVector get() = Icons.Rounded.Folder
    val Settings: ImageVector get() = Icons.Rounded.Settings
}
