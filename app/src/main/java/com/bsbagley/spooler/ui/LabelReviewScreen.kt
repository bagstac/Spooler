package com.bsbagley.spooler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Manual-entry form for a filament sourced from a printed label, with a
 * camera icon on each field (or field group) to capture just that value —
 * point the camera at the SKU, then the material, then the temp range, etc.
 * Every field stays editable regardless of what OCR found. Confirming can
 * either send straight to Spoolman or arm an NFC write — both reuse the
 * exact same pipelines as a scanned Anycubic tag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelReviewScreen(
    sendState: SendState,
    lookupState: LookupState,
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

    // Fills whatever's still blank once a lookup succeeds — never overwrites
    // a value the user typed or captured. Diameter is the one exception: its
    // "1.75" starting value is just a filler default, not user data, so a
    // found result replaces it too.
    LaunchedEffect(lookupState) {
        val found = (lookupState as? LookupState.Found)?.result ?: return@LaunchedEffect
        if (colorHex.isBlank()) found.colorHex?.let { colorHex = it.removePrefix("#").uppercase() }
        if (extMin.isBlank()) found.extruderMinC?.let { extMin = it.toString() }
        if (extMax.isBlank()) found.extruderMaxC?.let { extMax = it.toString() }
        if (bedMin.isBlank()) found.bedMinC?.let { bedMin = it.toString() }
        if (bedMax.isBlank()) found.bedMaxC?.let { bedMax = it.toString() }
        found.diameterMm?.let { diameter = it.toString() }
        if (length.isBlank()) found.lengthMeters?.let { length = it.toString() }
    }

    // Give the user a moment to read the confirmation, then return to the
    // main screen — mirrors how a successful tag scan already lands there.
    LaunchedEffect(sendState) {
        if (sendState is SendState.Success) {
            delay(1200)
            onBack()
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
        val d = diameter.toDoubleOrNull()
        if (diameter.isNotBlank() && d == null) { error = "Diameter must be a number."; return null }
        for ((label, v) in listOf("Extruder min" to extMin, "Extruder max" to extMax,
                                  "Bed min" to bedMin, "Bed max" to bedMax, "Length" to length)) {
            if (v.isNotBlank() && v.toIntOrNull() == null) { error = "$label must be a whole number."; return null }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Label Review Screen") },
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Type values in, or tap the camera icon to capture that field from a photo of the label.",
                style = MaterialTheme.typography.bodyMedium,
            )
            field(
                brand, { brand = it }, "Brand",
                { captureField = FilamentField.BRAND }, Modifier.fillMaxWidth(),
            )
            field(sku, { sku = it }, "SKU", { captureField = FilamentField.SKU }, Modifier.fillMaxWidth())
            field(
                material, { material = it }, "Material",
                { captureField = FilamentField.MATERIAL }, Modifier.fillMaxWidth(),
            )
            field(
                colorName, { colorName = it }, "Color name",
                { captureField = FilamentField.COLOR_NAME }, Modifier.fillMaxWidth(),
            )
            field(
                colorHex, { colorHex = it }, "Color hex (RRGGBB)",
                { captureField = FilamentField.COLOR }, Modifier.fillMaxWidth(),
            )
            Text(
                "The hex value isn't usually printed on labels — it's normally filled " +
                    "in automatically by the lookup below, or you can leave it and fix " +
                    "the color later in Spoolman.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val canLookup = brand.isNotBlank() && material.isNotBlank() && colorName.isNotBlank()
            if (canLookup) {
                OutlinedButton(
                    onClick = { onLookup(brand, material, colorName) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = lookupState != LookupState.Loading,
                ) {
                    if (lookupState == LookupState.Loading) {
                        CircularProgressIndicator(Modifier, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("Look up remaining details online")
                    }
                }
                when (val s = lookupState) {
                    is LookupState.Found -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "✓ Filled in from the Open Filament Database",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = onDismissLookup) { Text("Dismiss") }
                    }
                    is LookupState.NotFound -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDismissLookup) { Text("Dismiss") }
                    }
                    else -> Unit
                }
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
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { validateAndBuild()?.let(onSend) },
                modifier = Modifier.fillMaxWidth(),
                enabled = sendState != SendState.Sending,
            ) {
                if (sendState == SendState.Sending) {
                    CircularProgressIndicator(Modifier, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Send to Spoolman")
                }
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
