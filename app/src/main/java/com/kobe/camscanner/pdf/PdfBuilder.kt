package com.kobe.camscanner.pdf

import android.graphics.Bitmap
import com.kobe.camscanner.core.common.IoDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.ocr.OcrResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * PDF creation (SDS 16, 17, 20).
 *
 * Pages are written as JPEGs rather than lossless images: a scanned page is a photograph, and at
 * the quality levels in [com.kobe.camscanner.domain.model.PdfQuality] the difference is invisible
 * while the file is several times smaller — which matters a great deal when the next step is
 * WhatsApp.
 *
 * When OCR text is supplied, each word is drawn again in invisible rendering mode at the position
 * it was recognised. The result is a *searchable* PDF: the visible layer is the scan, and an
 * exactly-aligned text layer sits underneath it, so selecting, copying and searching all work in
 * any PDF reader.
 */
@Singleton
class PdfBuilder @Inject constructor(
    private val imageStore: ImageStore,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Renders [pages] to [target].
     *
     * @param ocrByPage optional recognised text, indexed the same way as [pages]. A null entry
     *   means that page contributes no text layer.
     */
    suspend fun build(
        pages: List<File>,
        target: File,
        options: PdfOptions,
        ocrByPage: List<OcrResult?> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File = withContext(dispatcher) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        target.parentFile?.mkdirs()

        val document = PDDocument()
        try {
            pages.forEachIndexed { index, pageFile ->
                coroutineContext.ensureActive()
                val bitmap = imageStore.decodeFile(pageFile, options.effectiveQuality.maxLongEdgePx)
                if (bitmap != null) {
                    addPage(document, bitmap, options, ocrByPage.getOrNull(index))
                    bitmap.recycle()
                }
                onProgress(index + 1, pages.size)
            }
            applyMetadata(document, options)
            document.save(target)
        } finally {
            document.close()
        }
        target
    }

    private fun addPage(
        document: PDDocument,
        bitmap: Bitmap,
        options: PdfOptions,
        ocr: OcrResult?,
    ) {
        val mediaBox = mediaBoxFor(bitmap, options)
        val page = PDPage(mediaBox)
        document.addPage(page)

        val image = JPEGFactory.createFromImage(
            document,
            bitmap,
            options.effectiveQuality.jpegQuality / 100f,
        )

        // Fit the page image inside the media box, preserving aspect ratio and centring what is
        // left over. A 4:3 photo on A4 therefore gets even bands rather than being stretched.
        val margin = options.marginPt
        val availableWidth = mediaBox.width - margin * 2
        val availableHeight = mediaBox.height - margin * 2
        val scale = min(availableWidth / bitmap.width, availableHeight / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val offsetX = (mediaBox.width - drawWidth) / 2f
        val offsetY = (mediaBox.height - drawHeight) / 2f

        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true).use { stream ->
            stream.drawImage(image, offsetX, offsetY, drawWidth, drawHeight)
            if (options.searchable && ocr != null && ocr.words.isNotEmpty()) {
                writeInvisibleText(stream, ocr, offsetX, offsetY, drawWidth, drawHeight)
            }
        }
    }

    /**
     * Draws each recognised word in [RenderingMode.NEITHER] — no fill, no stroke — at the place it
     * was found. Font size is derived from each word's own box height so the invisible glyphs track
     * the visible ones closely enough for text selection to feel correct.
     */
    private fun writeInvisibleText(
        stream: PDPageContentStream,
        ocr: OcrResult,
        offsetX: Float,
        offsetY: Float,
        drawWidth: Float,
        drawHeight: Float,
    ) {
        val font = PDType1Font.HELVETICA
        stream.beginText()
        stream.setRenderingMode(RenderingMode.NEITHER)

        var previousX = 0f
        var previousY = 0f
        var started = false

        ocr.words.forEach { word ->
            val text = word.text.filter { it.code in 32..255 }
            if (text.isBlank()) return@forEach

            val boxHeight = word.bounds.height() * drawHeight
            val boxWidth = word.bounds.width() * drawWidth
            if (boxHeight <= 0.5f || boxWidth <= 0.5f) return@forEach

            // PDF's origin is bottom-left; OCR boxes are top-left. Flip the y axis, then sit the
            // baseline slightly inside the box the way real type does.
            val x = offsetX + word.bounds.left * drawWidth
            val y = offsetY + drawHeight - (word.bounds.bottom * drawHeight) + boxHeight * 0.18f

            val fontSize = max(1f, boxHeight * 0.82f)
            stream.setFont(font, fontSize)

            // Horizontal scaling makes the invisible word span exactly the visible one, which is
            // what stops a selection highlight from drifting across a long line.
            val naturalWidth = runCatching {
                font.getStringWidth(text) / 1000f * fontSize
            }.getOrDefault(0f)
            val horizontalScale = if (naturalWidth > 0f) (boxWidth / naturalWidth) * 100f else 100f
            stream.setHorizontalScaling(horizontalScale.coerceIn(10f, 400f))

            if (!started) {
                stream.newLineAtOffset(x, y)
                started = true
            } else {
                stream.newLineAtOffset(x - previousX, y - previousY)
            }
            previousX = x
            previousY = y

            runCatching { stream.showText(text) }
        }

        stream.endText()
    }

    private fun mediaBoxFor(bitmap: Bitmap, options: PdfOptions): PDRectangle {
        if (options.pageSize.isOriginal) {
            // 1 px -> 1 pt at 72 dpi would make a 3000 px scan a metre wide, so map the long edge
            // onto A4's long edge and keep the image's own aspect ratio.
            val longEdge = max(bitmap.width, bitmap.height).toFloat()
            val scale = PDRectangle.A4.height / longEdge
            return PDRectangle(bitmap.width * scale, bitmap.height * scale)
        }
        val size = options.pageSize
        // Landscape pages get a landscape sheet rather than being shrunk into a portrait one.
        return if (bitmap.width > bitmap.height) {
            PDRectangle(size.heightPt, size.widthPt)
        } else {
            PDRectangle(size.widthPt, size.heightPt)
        }
    }

    private fun applyMetadata(document: PDDocument, options: PdfOptions) {
        val info = document.documentInformation
        val metadata = options.metadata
        if (metadata.title.isNotBlank()) info.title = metadata.title
        if (metadata.author.isNotBlank()) info.author = metadata.author
        if (metadata.subject.isNotBlank()) info.subject = metadata.subject
        if (metadata.keywords.isNotBlank()) info.keywords = metadata.keywords
        info.creator = CREATOR
        info.producer = CREATOR
        info.creationDate = java.util.Calendar.getInstance()
    }

    private companion object {
        /**
         * Identifies the producing app in the file, as any PDF writer does. It carries no user or
         * device identity — nothing in a Kobe PDF says who made it unless the user filled in the
         * author field themselves.
         */
        const val CREATOR = "Kobe CamScanner"
    }
}
