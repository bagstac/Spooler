package com.bsbagley.spooler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bsbagley.spooler.LookupState
import com.bsbagley.spooler.SendState
import com.bsbagley.spooler.ocr.FilamentField
import com.bsbagley.spooler.ocr.ParsedFields
import com.bsbagley.spooler.tag.FilamentInfo
import kotlinx.coroutines.delay

private const val TOTAL_FIELD_SLOTS = 9

/**
 * Manual-entry form for a filament sourced from a printed label, with a
 * camera icon on each field (or field group) to capture just that value —
 * point the camera at the SKU, then the material, then the temp range, etc.
 * Every field stays editable regardless of what OCR found.
 *
 * Leads with "Identify the Filament" (Brand/Material/Color name) — once all
 * three are filled in, the Open Filament Database lookup fires automatically
 * (debounced, so it doesn't fire on every keystroke) and back-fills whatever
 * it finds into the collapsed "Other Details" section (SKU, color hex, temps,
 * diameter, length). "Send to Spoolman" is pinned to the bottom as the one
 * primary action; "Write to NFC tag" stays in-flow as a secondary one. Both
 * reuse the exact same pipelines as a scanned Anycubic tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelReviewScreen(
    sendState: SendState,
    lookupState: LookupState,
    showWriteTagOption: Boolean,
    spoolmanEnabled: Boolean,
    onSend: (FilamentInfo) -> Unit,
    onArmWrite: (FilamentInfo) -> Unit,
    onLookup: (brand: String, material: String, colorName: String) -> Unit,
    onDismissLookup: () -> Unit,
    onBack: () -> Unit,
) {
    var brand by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var material by remember { mutableStateOf("") }
    var colorHex by remember { mutableStateOf("") }
    var colorName by remember { mutableStateOf("") }
    var extMin by remember { mutableStateOf("") }
    var extMax by remember { mutableStateOf("") }
    var bedMin by remember { mutableStateOf("") }
    var bedMax by remember { mutableStateOf("") }
    var diameter by remember { mutableStateOf("1.75") }
    var length by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var captureField by remember { mutableStateOf<FilamentField?>(null) }
    var showHexInfo by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-trigger the Open Filament Database lookup once Brand + Material +
    // Color name are all filled in. Keyed on the three values so editing any
    // of them restarts the debounce; the short delay avoids firing a lookup
    // on every keystroke while all three happen to already be non-blank.
    LaunchedEffect(brand, material, colorName) {
        if (brand.isNotBlank() && material.isNotBlank() && colorName.isNotBlank()) {
            delay(600)
            onLookup(brand, material, colorName)
        }
    }

    // Fills whatever's still blank once a lookup succeeds — never overwrites
    // a value the user typed or captured. Diameter is the one exception: its
    // "1.75" starting value is just a filler default, not user data, so a
    // found result replaces it too.
    LaunchedEffect(lookupState) {
        when (val s = lookupState) {
            is LookupState.Found -> {
                val found = s.result
                if (colorHex.isBlank()) found.colorHex?.let { colorHex = it.removePrefix("#").uppercase() }
                if (extMin.isBlank()) found.extruderMinC?.let { extMin = it.toString() }
                if (extMax.isBlank()) found.extruderMaxC?.let { extMax = it.toString() }
                if (bedMin.isBlank()) found.bedMinC?.let { bedMin = it.toString() }
                if (bedMax.isBlank()) found.bedMaxC?.let { bedMax = it.toString() }
                found.diameterMm?.let { diameter = it.toString() }
                if (length.isBlank()) found.lengthMeters?.let { length = it.toString() }
                snackbarHostState.showSnackbar("Filled in from the Open Filament Database")
                onDismissLookup()
            }
            is LookupState.NotFound -> {
                snackbarHostState.showSnackbar(s.message)
                onDismissLookup()
            }
            else -> Unit
        }
    }

    // Show the result as a snackbar, then — on success only — return to the
    // main screen once it's been read (showSnackbar suspends until the
    // snackbar dismisses), mirroring how a successful tag scan lands there.
    LaunchedEffect(sendState) {
        when (sendState) {
            is SendState.Success -> {
                snackbarHostState.showSnackbar(sendState.message)
                onBack()
            }
            is SendState.Error -> snackbarHostState.showSnackbar(sendState.message)
            else -> Unit
        }
    }

    captureField?.let { field ->
        Dialog(
            onDismissRequest = { captureField = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FieldCaptureScreen(
                field = field,
                onCancel = { captureField = null },
                onCaptured = { parsed ->
                    applyCapturedField(field, parsed,
                        setBrand = { brand = it },
                        setSku = { sku = it }, setMaterial = { material = it },
                        setColorHex = { colorHex = it }, setColorName = { colorName = it },
                        setExtMin = { extMin = it },
                        setExtMax = { extMax = it }, setBedMin = { bedMin = it },
                        setBedMax = { bedMax = it }, setDiameter = { diameter = it },
                        setLength = { length = it },
                    )
                    captureField = null
                },
            )
        }
    }

    fun validateAndBuild(): FilamentInfo? {
        if (!colorHex.matches(Regex("[0-9a-fA-F]{6}"))) {
            error = "Color must be 6 hex digits (RRGGBB)."; return null
        }
        // Tag ASCII fields are 16 bytes; numeric fields are unsigned int16.
        if (sku.trim().encodeToByteArray().size > 16) { error = "SKU is too long (max 16 bytes)."; return null }
        if (material.trim().encodeToByteArray().size > 16) { error = "Material is too long (max 16 bytes)."; return null }
        val d = diameter.toDoubleOrNull()
        if (diameter.isNotBlank() && d == null) { error = "Diameter must be a number."; return null }
        if (d != null && d !in 0.1..10.0) { error = "Diameter must be between 0.1 and 10 mm."; return null }
        for ((label, v) in listOf("Extruder min" to extMin, "Extruder max" to extMax,
                                  "Bed min" to bedMin, "Bed max" to bedMax, "Length" to length)) {
            if (v.isBlank()) continue
            val n = v.toIntOrNull()
            if (n == null) { error = "$label must be a whole number."; return null }
            if (n !in 0..65535) { error = "$label must be between 0 and 65535."; return null }
        }
        error = null
        val hex = colorHex.uppercase()
        return FilamentInfo(
            sku = sku.trim(),
            brandCode = "",
            brand = brand.trim().ifEmpty { "Unknown" },
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
    fun field(
        value: String,
        onChange: (String) -> Unit,
        label: String,
        onCapture: () -> Unit,
        modifier: Modifier = Modifier,
    ) = OutlinedTextField(
        value, onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onCapture) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = "Capture $label from photo")
            }
        },
    )

    val filledSlots = listOf(
        brand.isNotBlank(), sku.isNotBlank(), material.isNotBlank(),
        colorName.isNotBlank(), colorHex.isNotBlank(),
        extMin.isNotBlank() && extMax.isNotBlank(),
        bedMin.isNotBlank() && bedMax.isNotBlank(),
        diameter.isNotBlank(), length.isNotBlank(),
    ).count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Spool Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (spoolmanEnabled) {
                Surface(shadowElevation = 8.dp) {
                    Button(
                        onClick = { validateAndBuild()?.let(onSend) },
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
            Text(
                "Type values in, or tap the camera icon to capture that field from a photo of the label.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { filledSlots / TOTAL_FIELD_SLOTS.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "$filledSlots of $TOTAL_FIELD_SLOTS fields filled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FormSection(
                title = "Identify the Filament",
                subtitle = "Fill in these three and the rest is looked up automatically",
            ) {
                Text(
                    "Set Brand, Material, and Color name below — once all three are " +
                        "filled in, Spooler automatically looks up the printing temps, " +
                        "diameter, and length from the Open Filament Database.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                field(
                    brand, { brand = it }, "Brand",
                    { captureField = FilamentField.BRAND }, Modifier.fillMaxWidth(),
                )
                field(
                    material, { material = it }, "Material",
                    { captureField = FilamentField.MATERIAL }, Modifier.fillMaxWidth(),
                )
                field(
                    colorName, { colorName = it }, "Color name",
                    { captureField = FilamentField.COLOR_NAME }, Modifier.fillMaxWidth(),
                )
                val canLookup = brand.isNotBlank() && material.isNotBlank() && colorName.isNotBlank()
                if (canLookup) {
                    OutlinedButton(
                        onClick = { onLookup(brand, material, colorName) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = lookupState != LookupState.Loading,
                    ) {
                        if (lookupState == LookupState.Loading) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Looking up…")
                        } else {
                            Text("Look up again")
                        }
                    }
                }
            }

            FormSection(
                title = "Other Details",
                subtitle = "The lookup above usually fills these in",
                initiallyExpanded = false,
            ) {
                field(sku, { sku = it }, "SKU", { captureField = FilamentField.SKU }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    field(
                        colorHex, { colorHex = it }, "Color hex (RRGGBB)",
                        { captureField = FilamentField.COLOR }, Modifier.weight(1f),
                    )
                    IconButton(onClick = { showHexInfo = !showHexInfo }) {
                        Icon(Icons.Filled.Info, contentDescription = "About color hex")
                    }
                }
                if (showHexInfo) {
                    Text(
                        "The hex value isn't usually printed on labels — it's normally " +
                            "filled in automatically by the lookup above, or you can leave " +
                            "it and fix the color later in Spoolman.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Extruder temperature", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        extMin, { extMin = it },
                        label = { Text("Min °C") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        extMax, { extMax = it },
                        label = { Text("Max °C") }, modifier = Modifier.weight(1f), singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { captureField = FilamentField.EXTRUDER_TEMP }) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = "Capture extruder temp from photo")
                            }
                        },
                    )
                }

                Text("Bed temperature", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        bedMin, { bedMin = it },
                        label = { Text("Min °C") }, modifier = Modifier.weight(1f), singleLine = true,
                    )
                    OutlinedTextField(
                        bedMax, { bedMax = it },
                        label = { Text("Max °C") }, modifier = Modifier.weight(1f), singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { captureField = FilamentField.BED_TEMP }) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = "Capture bed temp from photo")
                            }
                        },
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field(
                        diameter, { diameter = it }, "Diameter mm",
                        { captureField = FilamentField.DIAMETER }, Modifier.weight(1f),
                    )
                    field(
                        length, { length = it }, "Length m",
                        { captureField = FilamentField.LENGTH }, Modifier.weight(1f),
                    )
                }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            if (showWriteTagOption) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { validateAndBuild()?.let(onArmWrite) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Write to NFC tag…") }
                Text(
                    "Arms a write; hold a blank/writable tag against the phone on the " +
                        "main screen afterward to give this spool an Anycubic-format tag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Applies only the fields relevant to [field] from a capture result — other properties in [parsed] are ignored. */
private fun applyCapturedField(
    field: FilamentField,
    parsed: ParsedFields,
    setBrand: (String) -> Unit,
    setSku: (String) -> Unit,
    setMaterial: (String) -> Unit,
    setColorHex: (String) -> Unit,
    setColorName: (String) -> Unit,
    setExtMin: (String) -> Unit,
    setExtMax: (String) -> Unit,
    setBedMin: (String) -> Unit,
    setBedMax: (String) -> Unit,
    setDiameter: (String) -> Unit,
    setLength: (String) -> Unit,
) {
    when (field) {
        FilamentField.BRAND -> parsed.brand?.let(setBrand)
        FilamentField.SKU -> parsed.sku?.let(setSku)
        FilamentField.MATERIAL -> parsed.material?.let(setMaterial)
        FilamentField.COLOR -> parsed.colorHex?.let(setColorHex)
        FilamentField.COLOR_NAME -> parsed.colorName?.let(setColorName)
        FilamentField.EXTRUDER_TEMP -> {
            parsed.extruderMinC?.let { setExtMin(it.toString()) }
            parsed.extruderMaxC?.let { setExtMax(it.toString()) }
        }
        FilamentField.BED_TEMP -> {
            parsed.bedMinC?.let { setBedMin(it.toString()) }
            parsed.bedMaxC?.let { setBedMax(it.toString()) }
        }
        FilamentField.DIAMETER -> parsed.diameterMm?.let { setDiameter(it.toString()) }
        FilamentField.LENGTH -> parsed.lengthMeters?.let { setLength(it.toString()) }
    }
}
