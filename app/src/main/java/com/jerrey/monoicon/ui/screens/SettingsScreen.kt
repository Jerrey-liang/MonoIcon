package com.jerrey.monoicon.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jerrey.monoicon.config.ConfigManager
import com.jerrey.monoicon.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MonoIcon settings screen (Phase 5).
 *
 * Master ON/OFF switch + theme selector. Both write the module's remote
 * preferences via [ConfigManager]; the launcher hook process picks up
 * changes within one refresh interval or on next launcher start.
 *
 * Also offers a one-tap HyperOS Launcher restart (Phase 6.5): reinstall /
 * theme change requires a launcher process restart to reload the module.
 */
@Composable
fun SettingsScreen() {
    var enabled by remember { mutableStateOf(ConfigManager.isEnabledFromUi()) }
    var selectedTheme by remember { mutableStateOf(ThemeManager.currentThemeId()) }
    var showRestartHint by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "MonoIcon",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Master switch ──────────────────────────────────────────

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Module Status",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Monochrome Icons")
                        Switch(
                            checked = enabled,
                            onCheckedChange = { value ->
                                enabled = value
                                showRestartHint = true
                                ConfigManager.setEnabled(value)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Theme selector (Phase 5) ─────────────────────────────────

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ThemeManager.availableThemes.forEach { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTheme == theme.id,
                                onClick = {
                                    selectedTheme = theme.id
                                    showRestartHint = true
                                    ThemeManager.setTheme(theme.id)
                                }
                            )
                            Text(
                                text = theme.name,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            // ── Quick launcher restart (Phase 6.5) ──────────────────────

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Launcher Restart",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            restarting = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    restartHyperOSLauncher(context)
                                }
                                Toast.makeText(
                                    context,
                                    if (ok) "HyperOS Launcher restarted"
                                    else "Restart failed — grant MonoIcon root access and retry",
                                    Toast.LENGTH_SHORT
                                ).show()
                                restarting = false
                            }
                        },
                        enabled = !restarting
                    ) {
                        Text(if (restarting) "Restarting…" else "Restart HyperOS Launcher")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Restart the launcher to reload the module after " +
                                "reinstall or theme switch. The first use asks for " +
                                "root access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Restart hint ───────────────────────────────────────────

            if (showRestartHint) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Configuration changed. Restart HyperOS Launcher " +
                            "to apply completely.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Info card ──────────────────────────────────────────────

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "MonoIcon converts HyperOS app icons to monochrome " +
                                "Material You themed icons. This module runs in the " +
                                "launcher process and requires LSPosed to function.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Target: com.miui.home (HyperOS Launcher)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Restarts the HyperOS launcher (com.miui.home).
 *
 * Primary path: `su` + `am force-stop` + `am start HOME` — deterministic on
 * rooted devices (LSPosed implies root). Falls back to
 * [ActivityManager.killBackgroundProcesses] when root is unavailable and
 * the launcher happens to be in the background.
 *
 * @return true when the launcher was restarted (or at least killed).
 */
private fun restartHyperOSLauncher(context: Context): Boolean {
    val pkg = "com.miui.home"
    val suOk = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf(
                "su", "-c",
                "am force-stop $pkg; " +
                    "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
            )
        )
        proc.waitFor() == 0
    } catch (_: Throwable) {
        false
    }
    if (suOk) return true
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(pkg)
        true
    } catch (_: Throwable) {
        false
    }
}
