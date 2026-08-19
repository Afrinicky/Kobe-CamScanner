package com.kobe.camscanner.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kobe.camscanner.core.common.DefaultDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Offline optical character recognition (SDS 19).
 *
 * ML Kit's *bundled* Latin text recogniser is used, not the Play-Services-backed one. The model
 * ships inside the APK, so recognition works in Airplane Mode on first launch with nothing to
 * download and nothing sent anywhere — which is the whole point of SDS 19 and 47.
 *
 * The recogniser is created once and closed with the process. It is thread-safe and holds a native
 * model; building one per page would dominate the cost of recognising a page.
 */
@Singleton
class OcrEngine @Inject constructor(
    private val imageStore: ImageStore,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private val recogniser: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Recognises text in a stored page image. */
    suspend fun recognise(pageFile: File): OcrResult = withContext(dispatcher) {
        val bitmap = imageStore.decodeFile(pageFile, MAX_OCR_EDGE) ?: return@withContext OcrResult.EMPTY
        val result = recognise(bitmap)
        bitmap.recycle()
        result
    }

    /**
     * Recognises text in a bitmap and returns both the plain text and the word geometry.
     *
     * Geometry is normalised to 0..1 so the searchable-PDF writer can place the invisible text
     * layer without knowing what resolution OCR happened to run at (SDS 20).
     */
    suspend fun recognise(bitmap: Bitmap): OcrResult = withContext(dispatcher) {
        if (bitmap.width < 16 || bitmap.height < 16) return@withContext OcrResult.EMPTY
        val input = InputImage.fromBitmap(bitmap, 0)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val text = suspendCancellableCoroutine<Text?> { continuation ->
            recogniser.process(input)
                .addOnSuccessListener { continuation.resume(it) }
                // A recognition failure is not exceptional — a blank or blurred page simply has no
                // text. It is reported as an empty result and surfaced by the caller as SDS 49's
                // "Text could not be recognised" only when the user explicitly asked for OCR.
                .addOnFailureListener { continuation.resume(null) }
                .addOnCanceledListener { continuation.resume(null) }
        } ?: return@withContext OcrResult.EMPTY

        val words = buildList {
            text.textBlocks.forEach { block ->
                block.lines.forEach { line ->
                    line.elements.forEach { element ->
                        val box = element.boundingBox ?: return@forEach
                        add(
                            OcrWord(
                                text = element.text,
                                bounds = RectF(
                                    box.left / width,
                                    box.top / height,
                                    box.right / width,
                                    box.bottom / height,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        val lines = buildList {
            text.textBlocks.forEach { block ->
                block.lines.forEach { line -> add(line.text) }
            }
        }

        OcrResult(text = text.text, lines = lines, words = words)
    }

    /** Recognises a whole document, page by page, reporting progress as it goes. */
    suspend fun recogniseAll(
        pages: List<File>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<OcrResult> = withContext(dispatcher) {
        pages.mapIndexed { index, file ->
            val result = recognise(file)
            onProgress(index + 1, pages.size)
            result
        }
    }

    companion object {
        /**
         * OCR accuracy plateaus around 2000 px on the long edge for ordinary print, while time and
         * memory keep climbing. Anything larger is downsampled first.
         */
        const val MAX_OCR_EDGE = 2000
    }
}

/** One recognised word and where it sits, in normalised page coordinates. */
data class OcrWord(
    val text: String,
    val bounds: RectF,
)

data class OcrResult(
    val text: String,
    val lines: List<String>,
    val words: List<OcrWord>,
) {
    val isEmpty: Boolean get() = text.isBlank()
    val wordCount: Int get() = words.size

    companion object {
        val EMPTY = OcrResult("", emptyList(), emptyList())
    }
}
