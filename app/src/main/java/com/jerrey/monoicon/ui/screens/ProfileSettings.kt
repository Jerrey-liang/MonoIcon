// Card/preference and confirmation layout adapted from KernelSU (GPL-3.0-or-later):
// manager/.../ui/screen/settings/SettingsMiuix.kt and ui/component/dialog/DialogMiuix.kt
// Upstream revision: 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.hook.HyperOs4PalettePublisher
import com.jerrey.monoicon.profile.CompatibilityProfile
import com.jerrey.monoicon.profile.ProfileRepository
import com.jerrey.monoicon.ui.LocalStrings
import com.jerrey.monoicon.ui.settings.settingsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** The Profile data and publishing pipeline are independent of the settings UI. */
@Composable
internal fun ProfileSettings(isActive: Boolean = true) {
    val context = LocalContext.current.applicationContext
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<CompatibilityProfile>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var resolution by remember { mutableStateOf<ProfileRepository.Resolution?>(null) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val busy = loading || working
    var message by rememberSaveable { mutableStateOf("") }
    // Keep the pending document across recreation while Android's picker is open.
    var exportProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val publishStatus by HyperOs4PalettePublisher.status.collectAsState()

    suspend fun refresh() {
        val snapshot = withContext(Dispatchers.IO) {
            Triple(ProfileRepository.catalog(context), ProfileRepository.selected(context), ProfileRepository.resolve(context))
        }
        profiles = snapshot.first.profiles
        selected = snapshot.second
        resolution = snapshot.third
        if (snapshot.first.errors.isNotEmpty()) message = snapshot.first.errors.joinToString("\n")
    }

    fun change(action: () -> Unit) {
        // A document result may arrive while the first catalog load is running.
        // Only another write should suppress it, otherwise imports are lost on recreation.
        if (working) return
        working = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    action()
                    HyperOs4PalettePublisher.profileChanged(context)
                }
                message = strings.profileRestart
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message.orEmpty()
            } finally {
                working = false
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) change {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open Profile" }
            ProfileRepository.import(context, ProfileRepository.readBounded(input))
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profileId = exportProfileId
        exportProfileId = null
        if (uri != null && profileId != null) {
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val profile = checkNotNull(ProfileRepository.catalog(context).profiles.find { it.id == profileId }) {
                            "Profile no longer exists"
                        }
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(profile.source) }
                    }
                    message = strings.profileExported
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    message = failure.message.orEmpty()
                } finally {
                    working = false
                }
            }
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            loading = true
            try {
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = failure.message.orEmpty()
            } finally {
                loading = false
            }
        }
    }

    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        OverlayDropdownPreference(
            title = strings.profiles,
            summary = resolution?.reason ?: strings.profileChecking,
            startAction = { ProfilePreferenceIcon(Icons.Rounded.Description, strings.profiles) },
            items = listOf(strings.profileAutomatic) + profiles.map { it.name },
            selectedIndex = profiles.indexOfFirst { it.id == selected } + 1,
            enabled = !busy,
            onSelectedIndexChange = { index ->
                val id = if (index == 0) null else profiles.getOrNull(index - 1)?.id
                change { ProfileRepository.select(context, id) }
            },
        )
        ArrowPreference(
            title = strings.profileImport,
            summary = strings.profileSummary,
            startAction = { ProfilePreferenceIcon(Icons.Rounded.FileOpen, strings.profileImport) },
            enabled = !busy,
            onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
        )
    }

    profiles.forEach { profile ->
        Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
            BasicComponent(
                title = profile.name,
                summary = buildString {
                    append("${profile.id} · r${profile.revision} · ")
                    append(if (profile.official) strings.profileOfficial else strings.profileCustom)
                    resolution?.candidates?.get(profile.id)?.takeIf(String::isNotBlank)?.let { append("\n$it") }
                },
                startAction = { ProfilePreferenceIcon(Icons.Rounded.Description, profile.name) },
            )
            ArrowPreference(
                title = strings.profileExport,
                startAction = { ProfilePreferenceIcon(Icons.Rounded.Save, strings.profileExport) },
                enabled = !busy,
                onClick = {
                    exportProfileId = profile.id
                    exporter.launch("${profile.id}.json")
                },
            )
            if (!profile.official) {
                ArrowPreference(
                    title = strings.profileDelete,
                    startAction = { ProfilePreferenceIcon(Icons.Rounded.Delete, strings.profileDelete) },
                    enabled = !busy,
                    onClick = { deleteProfileId = profile.id },
                )
            }
        }
    }

    Card(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth()) {
        ArrowPreference(
            title = strings.profileRefresh,
            summary = resolution?.environment?.takeIf(String::isNotBlank),
            startAction = { ProfilePreferenceIcon(Icons.Rounded.Refresh, strings.profileRefresh) },
            enabled = !busy,
            onClick = {
                loading = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ProfileRepository.invalidate() }
                        message = ""
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        message = failure.message.orEmpty()
                    } finally {
                        loading = false
                    }
                }
            },
        )
        BasicComponent(
            title = strings.profilePublication,
            summary = publishStatus,
            startAction = { ProfilePreferenceIcon(Icons.Rounded.Info, strings.profilePublication) },
        )
    }

    if (message.isNotBlank() || busy) {
        Text(
            text = if (busy) strings.profileChecking else message,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    val deleting = profiles.find { it.id == deleteProfileId && !it.official }
    WindowDialog(
        show = deleting != null,
        title = strings.profileDelete,
        onDismissRequest = { deleteProfileId = null },
    ) {
        Column {
            val note = if (strings.languageSystem == "跟随系统") {
                "删除“${deleting?.name.orEmpty()}”？如果正在使用此 Profile，将恢复自动匹配。"
            } else {
                "Delete “${deleting?.name.orEmpty()}”? If selected, automatic matching will be restored."
            }
            Text(text = note)
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                TextButton(
                    text = settingsText("cancel"),
                    onClick = { deleteProfileId = null },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = strings.profileDelete,
                    onClick = {
                        val id = deleting?.id
                        deleteProfileId = null
                        if (id != null) change { ProfileRepository.delete(context, id) }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun ProfilePreferenceIcon(icon: ImageVector, label: String) {
    Icon(
        imageVector = icon,
        modifier = Modifier.padding(end = 6.dp),
        contentDescription = label,
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
