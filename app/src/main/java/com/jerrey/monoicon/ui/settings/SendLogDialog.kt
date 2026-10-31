// Adapted from KernelSU SendLogDialog (GPL-3.0-or-later), commit 2fe188310309bd7a7c9b5a80beed6f14469c6cd2.
package com.jerrey.monoicon.ui.settings

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.jerrey.monoicon.BuildConfig
import com.jerrey.monoicon.logging.LogStore
import com.jerrey.monoicon.ui.LocalStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@Composable
fun SendLogDialog(show: Boolean, onDismissRequest: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val sendLogText = settingsText("send_log")
    var busy by remember { mutableStateOf(false) }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, strings.logsExportFailed, Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) perform {
            withContext(Dispatchers.IO) {
                LogStore.flushNow()
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(LogStore.exportText()) }
            }
            Toast.makeText(context, strings.logsExportDone, Toast.LENGTH_SHORT).show()
        }
    }
    OverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Text(
            text = sendLogText,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
            fontSize = MiuixTheme.textStyles.title4.fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurface,
        )
        ArrowPreference(
            title = settingsText("save_log"),
            enabled = !busy,
            startAction = { Icon(Icons.Rounded.Save, null, Modifier.padding(end = 16.dp)) },
            onClick = { exporter.launch(LogStore.exportFileName()); onDismissRequest() },
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        )
        ArrowPreference(
            title = sendLogText,
            enabled = !busy,
            startAction = { Icon(Icons.Rounded.Share, null, Modifier.padding(end = 16.dp)) },
            onClick = {
                perform {
                    val file = withContext(Dispatchers.IO) {
                        LogStore.flushNow()
                        val directory = File(context.cacheDir, "logs").apply { mkdirs() }
                        File(directory, "MonoIcon-log.txt").apply { writeText(LogStore.exportText()) }
                    }
                    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("MonoIcon log", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, sendLogText))
                    onDismissRequest()
                }
            },
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        )
        ArrowPreference(
            title = strings.logsTitle,
            startAction = { Icon(Icons.AutoMirrored.Rounded.Article, null, Modifier.padding(end = 16.dp)) },
            onClick = { onDismissRequest(); onOpenLogs() },
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        )
        TextButton(
            text = settingsText("cancel"),
            onClick = onDismissRequest,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp).padding(horizontal = 24.dp),
        )
    }
}
