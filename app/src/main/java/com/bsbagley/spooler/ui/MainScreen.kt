package com.bsbagley.spooler.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsbagley.spooler.NfcStatus
import com.bsbagley.spooler.ScanUiState
import com.bsbagley.spooler.ScanViewModel
import com.bsbagley.spooler.SendState
import com.bsbagley.spooler.WriteState
import com.bsbagley.spooler.data.ScanRecord
import com.bsbagley.spooler.tag.FilamentInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ScanViewModel,
    nfcStatus: NfcStatus,
    onOpenNfcSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val history by viewModel.history.collectAsState()
    val spoolmanUrl by viewModel.spoolmanUrl.collectAsState()
    val writeState by viewModel.writeState.collectAsState()
    val showRawDump by viewModel.showRawDump.collectAsState()
    val showHistorySetting by viewModel.showHistory.collectAsState()
    val showWriteTagSetting by viewModel.showWriteTag.collectAsState()
    val spoolmanEnabledSetting by viewModel.spoolmanEnabled.collectAsState()
    // Prompt for the Spoolman URL on first launch (nothing configured yet).
    var showSettings by remember { mutableStateOf(spoolmanUrl.isBlank()) }
    // Non-null while the full-screen raw memory page is open.
    var rawDumpRecord by remember { mutableStateOf<ScanRecord?>(null) }
    var showHistoryPage by remember { mutableStateOf(false) }
    var showLabelReview by remember { mutableStateOf(false) }
    val sendState by viewModel.sendState.collectAsState()
    val lookupState by viewModel.lookupState.collectAsState()

    rawDumpRecord?.let { record ->
        BackHandler { rawDumpRecord = null }
        RawMemoryScreen(record, onBack = { rawDumpRecord = null })
        return
    }

    if (showLabelReview) {
        BackHandler { showLabelReview = false }
        LabelReviewScreen(
            sendState = sendState,
            lookupState = lookupState,
            showWriteTagOption = showWriteTagSetting,
            spoolmanEnabled = spoolmanEnabledSetting,
            onSend = viewModel::sendManualEntryToSpoolman,
            onArmWrite = { info -> viewModel.armWrite(info); showLabelReview = false },
            onLookup = viewModel::lookupFilamentDetails,
            onDismissLookup = viewModel::resetLookup,
            onBack = { showLabelReview = false; viewModel.resetLookup() },
        )
        return
    }

    if (showHistoryPage) {
        BackHandler { showHistoryPage = false }
        HistoryScreen(
            history = history,
            onSelect = { viewModel.showRecord(it); showHistoryPage = false },
            onClear = viewModel::clearHistory,
            onBack = { showHistoryPage = false },
        )
        return
    }

    if (showSettings) {
        SettingsDialog(
            currentUrl = spoolmanUrl,
            showRawDump = showRawDump,
            showHistory = showHistorySetting,
            showWriteTag = showWriteTagSetting,
            spoolmanEnabled = spoolmanEnabledSetting,
            onSave = { url, rawDumpEnabled, historyEnabled, writeTagEnabled, spoolmanEnabled ->
                viewModel.setSpoolmanUrl(url)
                viewModel.setShowRawDump(rawDumpEnabled)
                viewModel.setShowHistory(historyEnabled)
                viewModel.setShowWriteTag(writeTagEnabled)
                viewModel.setSpoolmanEnabled(spoolmanEnabled)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Main Screen") },
                actions = {
                    if (uiState is ScanUiState.Result) {
                        IconButton(onClick = viewModel::clearResult) {
                            Icon(Icons.Filled.Refresh, contentDescription = "New scan")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (nfcStatus) {
                NfcStatus.UNSUPPORTED -> item {
                    StatusCard("This device has no NFC hardware.", isError = true)
                }
                NfcStatus.DISABLED -> item {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("NFC is turned off.", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = onOpenNfcSettings) { Text("Open NFC settings") }
                        }
                    }
                }
                NfcStatus.READY -> Unit
            }

            when (val write = writeState) {
                is WriteState.Armed -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Write armed", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Hold the target tag against the phone. Its filament data " +
                                    "(pages 4–31) will be overwritten; the UID and lock pages are never touched.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = viewModel::resetWrite) { Text("Cancel") }
                        }
                    }
                }
                WriteState.Writing -> item { StatusCard("Writing tag…", showProgress = true) }
                is WriteState.Success -> item {
                    Card {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "✓ ${write.message}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::resetWrite) { Text("Dismiss") }
                        }
                    }
                }
                is WriteState.Error -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                write.message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::resetWrite) { Text("Dismiss") }
                        }
                    }
                }
                WriteState.Idle -> Unit
            }

            when (val state = uiState) {
                ScanUiState.Idle -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Primary path: scanning is the app's core, hardware-driven
                        // interaction — given a heavier, tonal treatment.
                        ActionCard(
                            icon = Icons.Filled.Nfc,
                            title = "Scan a Tag",
                            description = "Hold an Anycubic filament tag against the back of the phone.",
                            primary = true,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text(
                                "OR",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            HorizontalDivider(Modifier.weight(1f))
                        }
                        // Fallback path: no card, no icon — a plain text button so
                        // it doesn't visually compete with the scan prompt above.
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "No tag? Enter details from a printed label instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                // Clear any stale lookup/send result from a previous
                                // spool before showing the fresh (blank) form.
                                viewModel.resetLookup()
                                viewModel.resetSend()
                                showLabelReview = true
                            }) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Enter New Spool")
                            }
                        }
                    }
                }
                ScanUiState.Reading -> item {
                    StatusCard("Reading tag…", showProgress = true)
                }
                is ScanUiState.Error -> item {
                    StatusCard(state.message, isError = true)
                }
                is ScanUiState.Result -> {
                    item {
                        ResultSection(
                            record = state.record,
                            viewModel = viewModel,
                            showRawDumpButton = showRawDump,
                            showWriteTagOption = showWriteTagSetting,
                            spoolmanEnabled = spoolmanEnabledSetting,
                            onViewRawDump = { rawDumpRecord = state.record },
                        )
                    }
                }
            }

            if (showHistorySetting && history.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { showHistoryPage = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("History (${history.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean = false, showProgress: Boolean = false) {
    Card(
        colors = if (isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showProgress) CircularProgressIndicator(Modifier.size(24.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The idle-state "Scan a Tag" option — icon, title, description, optional action. */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    primary: Boolean = false,
    content: @Composable (() -> Unit)? = null,
) {
    Card(
        colors = if (primary) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(if (primary) 44.dp else 36.dp),
                tint = if (primary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Text(
                title,
                style = if (primary) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                color = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (primary) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
            content?.let {
                Spacer(Modifier.size(4.dp))
                it()
            }
        }
    }
}

@Composable
private fun ResultSection(
    record: ScanRecord,
    viewModel: ScanViewModel,
    showRawDumpButton: Boolean,
    showWriteTagOption: Boolean,
    spoolmanEnabled: Boolean,
    onViewRawDump: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sendState by viewModel.sendState.collectAsState()
    var showWriteDialog by remember(record) { mutableStateOf(false) }

    if (showWriteDialog && record.filament != null) {
        WriteTagDialog(
            initial = record.filament,
            onArm = { viewModel.armWrite(it); showWriteDialog = false },
            onDismiss = { showWriteDialog = false },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        record.filament?.let { FilamentCard(it) }
        record.decodeError?.let {
            StatusCard("Read the tag, but couldn't decode it: $it", isError = true)
        }

        TagInfoCard(record)

        if (spoolmanEnabled && record.filament != null) {
            SendToSpoolmanRow(sendState, onSend = { viewModel.sendToSpoolman(record) })
        }

        // Secondary actions live behind an overflow menu so "Send to Spoolman"
        // reads as the one thing this screen wants you to do.
        var menuExpanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("More actions")
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
    }
}

/** Full-screen page for the per-page hex dump; opened from the "View raw memory" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawMemoryScreen(record: ScanRecord, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raw Memory Screen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            HexDumpCard(record.hex)
        }
    }
}

@Composable
private fun SendToSpoolmanRow(sendState: SendState, onSend: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSend, enabled = sendState != SendState.Sending) {
            if (sendState == SendState.Sending) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (sendState == SendState.Sending) "Sending…" else "Send to Spoolman")
        }
        when (sendState) {
            is SendState.Success -> Text(
                "✓ ${sendState.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            is SendState.Error -> Text(
                sendState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

/**
 * Edit-then-arm dialog: fields are prefilled from the last scan; confirming
 * arms a write so the next tag presented gets the encoded data.
 */
@Composable
private fun WriteTagDialog(
    initial: FilamentInfo,
    onArm: (FilamentInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    var sku by remember { mutableStateOf(initial.sku) }
    var material by remember { mutableStateOf(initial.material) }
    var colorHex by remember { mutableStateOf(initial.colorHex) }
    var extMin by remember { mutableStateOf(initial.extruderMinC?.toString() ?: "") }
    var extMax by remember { mutableStateOf(initial.extruderMaxC?.toString() ?: "") }
    var bedMin by remember { mutableStateOf(initial.bedMinC?.toString() ?: "") }
    var bedMax by remember { mutableStateOf(initial.bedMaxC?.toString() ?: "") }
    var diameter by remember { mutableStateOf(initial.diameterMm?.toString() ?: "1.75") }
    var length by remember { mutableStateOf(initial.lengthMeters?.toString() ?: "330") }
    var error by remember { mutableStateOf<String?>(null) }

    fun validateAndBuild(): FilamentInfo? {
        if (!colorHex.matches(Regex("[0-9a-fA-F]{6}"))) {
            error = "Color must be 6 hex digits (RRGGBB)."; return null
        }
        if (sku.encodeToByteArray().size > 16) { error = "SKU is too long (max 16 bytes)."; return null }
        if (material.encodeToByteArray().size > 16) { error = "Material is too long (max 16 bytes)."; return null }
        val d = diameter.toDoubleOrNull()
        if (diameter.isNotBlank() && d == null) { error = "Diameter must be a number."; return null }
        for ((label, v) in listOf("Extruder min" to extMin, "Extruder max" to extMax,
                                  "Bed min" to bedMin, "Bed max" to bedMax, "Length" to length)) {
            if (v.isNotBlank() && v.toIntOrNull() == null) { error = "$label must be a whole number."; return null }
        }
        val hex = colorHex.uppercase()
        return FilamentInfo(
            sku = sku.trim(),
            brandCode = "AC",
            brand = "Anycubic",
            material = material.trim(),
            colorHex = hex,
            colorAbgr = listOf(
                0xFF,
                hex.substring(4, 6).toInt(16),
                hex.substring(2, 4).toInt(16),
                hex.substring(0, 2).toInt(16),
            ),
            extruderMinC = extMin.toIntOrNull(),
            extruderMaxC = extMax.toIntOrNull(),
            bedMinC = bedMin.toIntOrNull(),
            bedMaxC = bedMax.toIntOrNull(),
            diameterMm = d,
            lengthMeters = length.toIntOrNull(),
        )
    }

    @Composable
    fun numField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) =
        OutlinedTextField(value, onChange, modifier = modifier, label = { Text(label) }, singleLine = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Write tag") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Arms a write with these values; the next tag you hold to the " +
                        "phone is overwritten. UID and lock pages are never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                numField(sku, { sku = it }, "SKU", Modifier.fillMaxWidth())
                numField(material, { material = it }, "Material", Modifier.fillMaxWidth())
                numField(colorHex, { colorHex = it }, "Color (RRGGBB)", Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    numField(extMin, { extMin = it }, "Extruder min °C", Modifier.weight(1f))
                    numField(extMax, { extMax = it }, "Extruder max °C", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    numField(bedMin, { bedMin = it }, "Bed min °C", Modifier.weight(1f))
                    numField(bedMax, { bedMax = it }, "Bed max °C", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    numField(diameter, { diameter = it }, "Diameter mm", Modifier.weight(1f))
                    numField(length, { length = it }, "Length m", Modifier.weight(1f))
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { validateAndBuild()?.let(onArm) }) { Text("Arm write") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsDialog(
    currentUrl: String,
    showRawDump: Boolean,
    showHistory: Boolean,
    showWriteTag: Boolean,
    spoolmanEnabled: Boolean,
    onSave: (
        url: String,
        showRawDump: Boolean,
        showHistory: Boolean,
        showWriteTag: Boolean,
        spoolmanEnabled: Boolean,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(currentUrl) }
    var rawDumpEnabled by remember { mutableStateOf(showRawDump) }
    var historyEnabled by remember { mutableStateOf(showHistory) }
    var writeTagEnabled by remember { mutableStateOf(showWriteTag) }
    var spoolmanEnabledState by remember { mutableStateOf(spoolmanEnabled) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Where is your Spoolman instance? Scanned spools are sent " +
                        "to its REST API. You can change this any time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Spoolman URL") },
                    placeholder = { Text("http://192.168.x.x:7912") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable Spoolman", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds \"Send to Spoolman\" buttons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = spoolmanEnabledState, onCheckedChange = { spoolmanEnabledState = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show raw memory dump", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds a \"View raw memory\" button on scans",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = rawDumpEnabled, onCheckedChange = { rawDumpEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show history", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds a \"History\" button listing past scans",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = historyEnabled, onCheckedChange = { historyEnabled = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show write NFC tag option", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds \"Write tag…\" / \"Write to NFC tag…\" buttons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = writeTagEnabled, onCheckedChange = { writeTagEnabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(url, rawDumpEnabled, historyEnabled, writeTagEnabled, spoolmanEnabledState)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FilamentCard(info: FilamentInfo) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Filament", style = MaterialTheme.typography.titleMedium)
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
}

private fun tempRange(min: Int?, max: Int?): String = when {
    min != null && max != null -> "$min–$max °C"
    min != null -> "$min °C"
    max != null -> "$max °C"
    else -> "—"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ColorRow(colorHex: String) {
    val swatch = remember(colorHex) {
        colorHex.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Color",
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (swatch != null) {
            Box(
                Modifier
                    .size(18.dp)
                    .background(swatch, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text("#$colorHex", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TagInfoCard(record: ScanRecord) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tag", style = MaterialTheme.typography.titleMedium)
            InfoRow("UID", record.uidHex)
            InfoRow("ATQA", "0x${record.atqaHex}")
            InfoRow("SAK", "0x%02X".format(record.sak))
            InfoRow("Pages read", "${record.pagesRead}${if (record.complete) "" else " (partial)"}")
            InfoRow("Scanned", TIME_FORMAT.format(Instant.ofEpochMilli(record.timestampMillis)))
        }
    }
}

@Composable
private fun HexDumpCard(hex: String) {
    val lines = remember(hex) { hexToPageLines(hex) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Raw memory", style = MaterialTheme.typography.titleMedium)
            Text(
                lines.joinToString("\n"),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

/** "P04  7B 00 65 00  {.e." — one line per 4-byte Type 2 page. */
private fun hexToPageLines(hex: String): List<String> {
    val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
    return bytes.chunked(4).mapIndexed { page, chunk ->
        val hexPart = chunk.joinToString(" ") { "%02X".format(it) }
        val asciiPart = chunk.map { if (it in 0x20..0x7E) it.toChar() else '·' }
            .joinToString("")
        "P%02d  %-11s  %s".format(page, hexPart, asciiPart)
    }
}

/** Full-screen page listing past scans; opened from the "History" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    history: List<ScanRecord>,
    onSelect: (ScanRecord) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History Screen (${history.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onClear) { Text("Clear") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(history, key = { it.timestampMillis }) { record ->
                HistoryRow(record, onClick = { onSelect(record) })
            }
        }
    }
}

@Composable
private fun HistoryRow(record: ScanRecord, onClick: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onClick)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            val title = record.filament?.let {
                listOf(it.material, it.sku).filter(String::isNotEmpty).joinToString(" · ")
            } ?: "Undecoded scan"
            Text(title.ifEmpty { "Undecoded scan" }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${TIME_FORMAT.format(Instant.ofEpochMilli(record.timestampMillis))}  ·  UID ${record.uidHex}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
