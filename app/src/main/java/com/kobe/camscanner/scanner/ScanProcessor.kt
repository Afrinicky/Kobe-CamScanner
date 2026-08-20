package com.kobe.camscanner.scanner

import android.graphics.Bitmap
import android.net.Uri
import com.kobe.camscanner.core.common.DefaultDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.domain.model.ScanFilter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a captured frame into a finished page.
 *
 * Full-resolution work happens here and only here, after capture — the live preview never touches
 * a frame this large (SDS 45). Every method suspends onto the CPU dispatcher, so nothing in this
 * class can block the main thread even if a caller forgets.
 */
@Singleton
class ScanProcessor @Inject constructor(
    private val detector: DocumentDetector,
    private val perspective: PerspectiveTransformer,
    private val enhancer: ImageEnhancer,
    private val imageStore: ImageStore,
    private val storage: KobeStorage,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Ingests a freshly captured JPEG: stores the original, finds the boundary, and produces the
     * corrected, enhanced page. [suggestedQuad] is the boundary the viewfinder was already showing;
     * re-using it keeps the result identical to what the user saw, and skips a second detection.
     */
    suspend fun ingestCapture(
        sessionId: String,
        capturedFile: File,
        suggestedQuad: Quad? = null,
        filter: ScanFilter = ScanFilter.AUTO,
    ): ProcessedPage? = withContext(dispatcher) {
        val pageId = UUID.randomUUID().toString().take(8)
        val original = storage.originalFile(sessionId, pageId)
        if (capturedFile.absolutePath != original.absolutePath) {
            capturedFile.copyTo(original, overwrite = true)
            capturedFile.delete()
        }
        processOriginal(sessionId, pageId, original, suggestedQuad, filter)
    }

    /** Ingests an image chosen from the gallery, through the identical pipeline (SDS 26). */
    suspend fun ingestUri(
        sessionId: String,
        uri: Uri,
        filter: ScanFilter = ScanFilter.AUTO,
    ): ProcessedPage? = withContext(dispatcher) {
        val pageId = UUID.randomUUID().toString().take(8)
        val original = storage.originalFile(sessionId, pageId)
        val bitmap = imageStore.decodeUri(uri, MAX_CAPTURE_EDGE) ?: return@withContext null
        imageStore.writeJpeg(bitmap, original, quality = 95)
        bitmap.recycle()
        processOriginal(sessionId, pageId, original, suggestedQuad = null, filter = filter)
    }

    /**
     * Re-renders a page after the user changed its corners, filter, adjustments or rotation.
     * Always starts from the stored original, so edits never compound on top of each other.
     */
    suspend fun reprocess(
        sessionId: String,
        pageId: String,
        originalFile: File,
        quad: Quad?,
        filter: ScanFilter,
        adjustments: Adjustments,
        rotationDegrees: Int,
    ): ProcessedPage? = withContext(dispatcher) {
        val source = imageStore.decodeFile(originalFile, MAX_CAPTURE_EDGE) ?: return@withContext null
        render(sessionId, pageId, originalFile, source, quad, filter, adjustments, rotationDegrees)
    }

    /** Detects a boundary on an already-stored original, for the manual crop screen's "reset". */
    suspend fun detectBoundary(originalFile: File): Quad? = withContext(dispatcher) {
        val bitmap = imageStore.decodeFile(originalFile, DETECT_EDGE) ?: return@withContext null
        val detection = detector.detectFromBitmap(bitmap)
        bitmap.recycle()
        detection.quad
    }

    /**
     * Renders a small preview for the filter strip. Deliberately tiny: eight filters at
     * [PREVIEW_EDGE] px cost less than one full-size render, which is what lets the strip appear
     * instantly instead of trickling in.
     */
    suspend fun filterPreview(
        source: Bitmap,
        filter: ScanFilter,
        adjustments: Adjustments = Adjustments.NEUTRAL,
        maxEdge: Int = PREVIEW_EDGE,
    ): Bitmap = withContext(dispatcher) {
        val longEdge = maxOf(source.width, source.height)
        val small = if (longEdge > maxEdge) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * maxEdge / longEdge).coerceAtLeast(1),
                (source.height * maxEdge / longEdge).coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        val result = enhancer.apply(small, filter, adjustments)
        if (small !== source) small.recycle()
        result
    }

    private suspend fun processOriginal(
        sessionId: String,
        pageId: String,
        original: File,
        suggestedQuad: Quad?,
        filter: ScanFilter,
    ): ProcessedPage? {
        val source = imageStore.decodeFile(original, MAX_CAPTURE_EDGE) ?: return null
        val quad = suggestedQuad ?: run {
            val detection = detector.detectFromBitmap(source)
            detection.quad
        }
        return render(
            sessionId = sessionId,
            pageId = pageId,
            originalFile = original,
            source = source,
            quad = quad,
            filter = filter,
            adjustments = Adjustments.NEUTRAL,
            rotationDegrees = 0,
        )
    }

    private fun render(
        sessionId: String,
        pageId: String,
        originalFile: File,
        source: Bitmap,
        quad: Quad?,
        filter: ScanFilter,
        adjustments: Adjustments,
        rotationDegrees: Int,
    ): ProcessedPage? {
        var working = source
        try {
            if (quad != null && quad != Quad.FULL) {
                val corrected = perspective.correct(working, quad)
                if (corrected !== working) working.recycle()
                working = corrected
            }

            val enhanced = enhancer.apply(working, filter, adjustments)
            if (enhanced !== working) working.recycle()
            working = enhanced

            if (rotationDegrees % 360 != 0) {
                working = imageStore.rotate(working, rotationDegrees.toFloat())
            }

            val processed = storage.processedFile(sessionId, pageId)
            imageStore.writeJpeg(working, processed, quality = 96)

            val thumbnail = storage.thumbnailFile("$sessionId-$pageId")
            imageStore.writeThumbnail(working, thumbnail, edge = THUMBNAIL_EDGE)

            return ProcessedPage(
                pageId = pageId,
                originalFile = originalFile,
                processedFile = processed,
                thumbnailFile = thumbnail,
                quad = quad,
                filter = filter,
                adjustments = adjustments,
                rotationDegrees = rotationDegrees,
                widthPx = working.width,
                heightPx = working.height,
            )
        } catch (t: Throwable) {
            return null
        } finally {
            if (!working.isRecycled) working.recycle()
        }
    }

    data class ProcessedPage(
        val pageId: String,
        val originalFile: File,
        val processedFile: File,
        val thumbnailFile: File,
        val quad: Quad?,
        val filter: ScanFilter,
        val adjustments: Adjustments,
        val rotationDegrees: Int,
        val widthPx: Int,
        val heightPx: Int,
    )

    companion object {
        /**
         * Cap on the long edge of a page image. 3000 px across A4 is about 250 dpi — past what any
         * office printer resolves, and the point where memory pressure starts costing more than the
         * detail is worth on a mid-range device.
         */
        const val MAX_CAPTURE_EDGE = 3000

        /** Detection re-runs use a proxy; anything larger is wasted work. */
        const val DETECT_EDGE = 1024

        const val THUMBNAIL_EDGE = 512
        const val PREVIEW_EDGE = 320
    }
}
