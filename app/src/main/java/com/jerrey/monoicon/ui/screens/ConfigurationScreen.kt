package com.jerrey.monoicon.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Standalone Profile page, using KernelSU SettingsMiuix's 12 dp card gutters. */
@Composable
fun ConfigurationScreen(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
) {
    Column(modifier = modifier.padding(horizontal = 12.dp)) {
        ProfileSettings(isActive = isActive)
    }
}
