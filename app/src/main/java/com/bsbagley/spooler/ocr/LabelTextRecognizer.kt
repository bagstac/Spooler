package com.bsbagley.spooler.ocr

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR for printed filament spool labels, via ML Kit's bundled Latin
 * text model (no network, no Play Services model download).
 *
 * v1 is deliberately "raw text only" — label layouts vary too much across
 * brands to auto-parse reliably, so the recognized text is shown to the user
 * as a reference for manual entry rather than mapped into fields directly.
 */
object LabelTextRecognizer {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Must be called with the ImageProxy from a CameraX ImageCapture result; closes it. */
    suspend fun recognize(imageProxy: ImageProxy): String =
        imageProxy.use {
            val mediaImage = it.image
                ?: throw IllegalStateException("Captured frame had no backing image")
            val input = InputImage.fromMediaImage(mediaImage, it.imageInfo.rotationDegrees)
            recognizeText(input)
        }

    private suspend fun recognizeText(input: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(input)
                .addOnSuccessListener { result -> cont.resume(result.text) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }
}
