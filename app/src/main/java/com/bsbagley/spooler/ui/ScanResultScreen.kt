package com.bsbagley.spooler.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.bsbagley.spooler.ScanViewModel
import com.bsbagley.spooler.SendState
import com.bsbagley.spooler.data.ScanRecord
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Full-screen page shown after a tag scan decodes — a dedicated page (rather
 * than a section on Main Screen) so it can share the same sectioned,
 * tonal-header look as Spool Info, with "Send to Spoolman" pinned to the
 * bottom as the one primary action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultScreen(
    record: ScanRecord,
    viewModel: ScanViewModel,
    sendState: SendState,
    showRawDumpButton: Boolean,
    showWriteTagOption: Boolean,
    spoolmanEnabled: Boolean,
    onViewRawDump: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showWriteDialog by remember(record) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Mirrors Spool Info: a moment to read the confirmation, then back to
    // Main Screen ready for the next scan. Errors get a snackbar instead of
    // a permanently-shown line.
    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Success -> {
                delay(1200)
                onBack()
            }
            is SendState.Error -> snackbarHostState.showSnackbar(sendState.message)
            else -> Unit
        }
    }

    if (showWriteDialog && record.filament != null) {
        WriteTagDialog(
            initial = record.filament,
            onArm = { viewModel.armWrite(it); showWriteDialog = false; onBack() },
            onDismiss = { showWriteDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Result") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy JSON") },
                                onClick = {
                                    clipboard.setText(AnnotatedString(viewModel.recordJson(record)))
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, viewModel.recordJson(record))
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share scan"))
                                    menuExpanded = false
                                },
                            )
                            if (showWriteTagOption && record.filament != null) {
                                DropdownMenuItem(
                                    text = { Text("Write tag…") },
                                    onClick = { showWriteDialog = true; menuExpanded = false },
                                )
                            }
                            if (showRawDumpButton) {
                                DropdownMenuItem(
                                    text = { Text("View raw memory") },
                                    onClick = { onViewRawDump(); menuExpanded = false },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (spoolmanEnabled && record.filament != null) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = { viewModel.sendToSpoolman(record) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        enabled = sendState != SendState.Sending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        if (sendState == SendState.Sending) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Sending…")
                        } else {
                            Text("Send to Spoolman")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(12.dp))

            record.decodeError?.let {
                FormSection(title = "Decode Error") {
                    Text(
                        "Read the tag, but couldn't decode it: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            record.filament?.let { info ->
                FormSection(title = "Filament") {
                    InfoRow("SKU", info.sku.ifEmpty { "—" })
                    InfoRow("Brand", info.brand.ifEmpty { "—" })
                    InfoRow("Material", info.material.ifEmpty { "—" })
                    ColorRow(info.colorHex)
                    InfoRow("Extruder", tempRange(info.extruderMinC, info.extruderMaxC))
                    InfoRow("Bed", tempRange(info.bedMinC, info.bedMaxC))
                    InfoRow("Diameter", info.diameterMm?.let { "$it mm" } ?: "—")
                    InfoRow("Length", info.lengthMeters?.let { "$it m" } ?: "—")
                }
            }

            FormSection(title = "Tag Info", initiallyExpanded = record.filament == null) {
                InfoRow("UID", record.uidHex)
                InfoRow("ATQA", "0x${record.atqaHex}")
                InfoRow("SAK", "0x%02X".format(record.sak))
                InfoRow("Pages read", "${record.pagesRead}${if (record.complete) "" else " (partial)"}")
                InfoRow("Scanned", TIME_FORMAT.format(Instant.ofEpochMilli(record.timestampMillis)))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
