package com.jerrey.monoicon.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jerrey.monoicon.logging.LogExport
import com.jerrey.monoicon.logging.LogStore
import com.jerrey.monoicon.ui.LocalStrings
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** How often the page re-reads [LogStore] while it is on screen. */
private const val POLL_INTERVAL_MS = 500L

/**
 * Runtime log page (Phase 14).
 *
 * Shows what [LogStore] recorded in this process — the module's own view of what
 * it did — rather than the previous approach of shelling out to `su logcat`,
 * which needed root and displayed other processes' lines.
 *
 * Being backed by a process-wide store, the list survives leaving the page,
 * recomposition and Activity recreation: coming back simply re-reads it.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier, isActive: Boolean = true) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lines by remember { mutableStateOf(visibleLines()) }

    // Poll the store's revision counter rather than subscribing to it, so the
    // store stays free of any Compose dependency and can run in the launcher
    // process too. Only a changed revision rebuilds the list.
    LaunchedEffect(lifecycleOwner, isActive) {
        if (!isActive) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var renderedRevision = -1L
            while (true) {
                val revision = LogStore.revision()
                if (revision != renderedRevision) {
                    renderedRevision = revision
                    lines = visibleLines()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogExport.MIME_TYPE)
    ) { uri ->
        // A null uri is the user cancelling the picker — nothing to report.
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = LogExport.writeTo(context, uri)
        Toast.makeText(
            context,
            if (ok) strings.logsExportDone else strings.logsExportFailed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
            BasicComponent(
                title = strings.logsExport,
                summary = strings.logsExportSummary,
                onClick = { exporter.launch(LogExport.suggestedFileName()) },
            )
        }

        LogLines(lines = lines, emptyText = strings.logsEmpty)
    }
}

/**
 * The most recent entries, oldest first.
 *
 * Only the tail is rendered — a long session can hold far more than a user
 * will scroll through, and the export always writes the complete history.
 */
private fun visibleLines(): List<String> {
    val entries = LogStore.snapshot()
    val start = (entries.size - LogStore.MAX_VISIBLE_ENTRIES).coerceAtLeast(0)
    return entries.subList(start, entries.size).map { LogStore.format(it) }
}

@Composable
private fun LogLines(lines: List<String>, emptyText: String) {
    Card(modifier = Modifier.padding(horizontal = PageSpacing.gutter)) {
        // No inner verticalScroll: this page already lives inside the host's
        // scrolling column, and a nested same-axis scrollable would be measured
        // with infinite height constraints (crash).
        //
        // Rendered as one Text rather than a LazyColumn: every entry is a
        // single monospace line, so a list would add item overhead without
        // changing what the user sees.
        Column(modifier = Modifier.padding(12.dp)) {
            if (lines.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = remember(lines) { lines.joinToString("\n") },
                    style = MiuixTheme.textStyles.footnote2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .background(
                            color = MiuixTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                )
            }
        }
    }
}
