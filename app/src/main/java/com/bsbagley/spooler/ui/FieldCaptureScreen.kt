package com.bsbagley.spooler.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bsbagley.spooler.ocr.FilamentField
import com.bsbagley.spooler.ocr.LabelFieldParser
import com.bsbagley.spooler.ocr.LabelTextRecognizer
import com.bsbagley.spooler.ocr.ParsedFields
import kotlinx.coroutines.launch

/**
 * Full-screen single-shot capture for one filament field, shown inside a
 * Dialog over [LabelReviewScreen]. Point the camera at just that part of the
 * label; OCR + the field parser try to lift the value out, with a
 * confirm/retake step and a manual-entry fallback for anything the parser
 * (or a blank label) can't supply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldCaptureScreen(field: FilamentField, onCaptured: (ParsedFields) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Field Capture Screen — ${field.label}") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!hasCameraPermission) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Camera access is needed to capture this field.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant camera permission")
                    }
                }
            } else {
                FieldCameraContent(field = field, onCaptured = onCaptured)
            }
        }
    }
}

private sealed interface FieldCaptureUiState {
    data object Preview : FieldCaptureUiState
    data object Recognizing : FieldCaptureUiState
    data class Confirm(val parsed: ParsedFields) : FieldCaptureUiState
    data object Manual : FieldCaptureUiState
    data class Error(val message: String) : FieldCaptureUiState
}

@Composable
private fun FieldCameraContent(field: FilamentField, onCaptured: (ParsedFields) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FieldCaptureUiState>(FieldCaptureUiState.Preview) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            Card(Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                Text(field.instructions, Modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
            }
            if (state is FieldCaptureUiState.Recognizing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            (state as? FieldCaptureUiState.Error)?.let { err ->
                Card(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text(err.message, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        when (val s = state) {
            is FieldCaptureUiState.Confirm -> ConfirmFieldPanel(
                field = field,
                parsed = s.parsed,
                onUse = { onCaptured(s.parsed) },
                onRetake = { state = FieldCaptureUiState.Preview },
                onEnterManually = { state = FieldCaptureUiState.Manual },
            )
            FieldCaptureUiState.Manual -> ManualFieldPanel(
                field = field,
                onSave = onCaptured,
                onBackToCamera = { state = FieldCaptureUiState.Preview },
            )
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        state = FieldCaptureUiState.Recognizing
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    scope.launch {
                                        state = try {
                                            val text = LabelTextRecognizer.recognize(image)
                                            FieldCaptureUiState.Confirm(LabelFieldParser.parse(text))
                                        } catch (e: Exception) {
                                            FieldCaptureUiState.Error(
                                                "Couldn't read text (${e.message}). Try again with better lighting.",
                                            )
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    state = FieldCaptureUiState.Error("Capture failed: ${exception.message}")
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state !is FieldCaptureUiState.Recognizing,
                ) { Text("Capture") }
                OutlinedButton(
                    onClick = { state = FieldCaptureUiState.Manual },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enter manually instead") }
            }
        }
    }
}

@Composable
private fun ConfirmFieldPanel(
    field: FilamentField,
    parsed: ParsedFields,
    onUse: () -> Unit,
    onRetake: () -> Unit,
    onEnterManually: () -> Unit,
) {
    val detected = displayValue(field, parsed)
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (detected != null) {
            Text("Detected", style = MaterialTheme.typography.titleMedium)
            Card { Text(detected, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
            Button(onClick = onUse, modifier = Modifier.fillMaxWidth()) { Text("Use this") }
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Retake") }
            TextButton(onClick = onEnterManually, modifier = Modifier.fillMaxWidth()) {
                Text("That's wrong — enter manually")
            }
        } else {
            Text(
                "Couldn't detect the ${field.label.lowercase()} in that photo.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Retake") }
            Button(onClick = onEnterManually, modifier = Modifier.fillMaxWidth()) { Text("Enter manually") }
        }
    }
}

@Composable
private fun ManualFieldPanel(field: FilamentField, onSave: (ParsedFields) -> Unit, onBackToCamera: () -> Unit) {
    // A fresh TextField inside this Dialog (which also hosts the CameraX
    // PreviewView) doesn't reliably grab focus/keyboard from a tap alone —
    // request both explicitly as soon as manual entry appears.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(field) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Enter ${field.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
        when (field) {
            FilamentField.EXTRUDER_TEMP, FilamentField.BED_TEMP -> {
                var min by remember { mutableStateOf("") }
                var max by remember { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        min, { min = it },
                        label = { Text("Min °C") },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        max, { max = it },
                        label = { Text("Max °C") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
                Button(
                    onClick = {
                        val fields = if (field == FilamentField.EXTRUDER_TEMP) {
                            ParsedFields(extruderMinC = min.toIntOrNull(), extruderMaxC = max.toIntOrNull())
                        } else {
                            ParsedFields(bedMinC = min.toIntOrNull(), bedMaxC = max.toIntOrNull())
                        }
                        onSave(fields)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }
            else -> {
                var value by remember { mutableStateOf("") }
                OutlinedTextField(
                    value, { value = it },
                    label = { Text(field.label) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        onSave(
                            when (field) {
                                FilamentField.BRAND -> ParsedFields(brand = value)
                                FilamentField.SKU -> ParsedFields(sku = value)
                                FilamentField.MATERIAL -> ParsedFields(material = value)
                                FilamentField.COLOR -> ParsedFields(
                                    colorHex = value.removePrefix("#").uppercase(),
                                )
                                FilamentField.COLOR_NAME -> ParsedFields(colorName = value)
                                FilamentField.DIAMETER -> ParsedFields(diameterMm = value.toDoubleOrNull())
                                FilamentField.LENGTH -> ParsedFields(lengthMeters = value.toIntOrNull())
                                else -> ParsedFields()
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }
            }
        }
        TextButton(onClick = onBackToCamera, modifier = Modifier.fillMaxWidth()) { Text("Back to camera") }
    }
}

private fun displayValue(field: FilamentField, p: ParsedFields): String? = when (field) {
    FilamentField.BRAND -> p.brand
    FilamentField.SKU -> p.sku
    FilamentField.MATERIAL -> p.material
    FilamentField.COLOR -> p.colorHex?.let { "#$it" }
    FilamentField.COLOR_NAME -> p.colorName
    FilamentField.EXTRUDER_TEMP -> formatRange(p.extruderMinC, p.extruderMaxC)
    FilamentField.BED_TEMP -> formatRange(p.bedMinC, p.bedMaxC)
    FilamentField.DIAMETER -> p.diameterMm?.let { "$it mm" }
    FilamentField.LENGTH -> p.lengthMeters?.let { "$it m" }
}

private fun formatRange(min: Int?, max: Int?): String? = when {
    min != null && max != null -> "$min–$max °C"
    min != null -> "$min °C"
    max != null -> "$max °C"
    else -> null
}
