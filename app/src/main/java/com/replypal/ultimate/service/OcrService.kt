package com.replypal.ultimate.service

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class OcrResult(val text: String, val usedDevanagari: Boolean)

class OcrService(private val context: Context) {
    suspend fun read(uri: Uri, preferDevanagari: Boolean = false): OcrResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = if (preferDevanagari) {
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        } else {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        try {
            val result = Tasks.await(recognizer.process(image))
            OcrResult(result.text.trim(), preferDevanagari)
        } finally {
            recognizer.close()
        }
    }
}
