package com.kobe.camscanner.domain.usecase

import com.kobe.camscanner.ai.SmartNaming
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.common.KobeResult
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.repository.ScanSessionState
import com.kobe.camscanner.data.repository.SessionPage
import com.kobe.camscanner.domain.model.PdfMetadata
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.ScanPage
import com.kobe.camscanner.ocr.OcrEngine
import com.kobe.camscanner.ocr.OcrResult
import com.kobe.camscanner.pdf.PdfBuilder
import javax.inject.Inject

/**
 * Turns the in-progress scan into a saved, searchable document.
 *
 * This is the single place that runs the tail of the SDS 5 workflow — OCR, PDF, name, save — so the
 * "Save" button on the review screen and the "Save" button on the page editor cannot drift apart.
 * Progress is reported step by step because on a ten-page scan this takes seconds, and SDS 54 asks
 * that anything not instantaneous show visible progress.
 */
class SaveScanSession @Inject constructor(
    private val session: ScanSession,
    private val repository: DocumentRepository,
    private val ocrEngine: OcrEngine,
    private val pdfBuilder: PdfBuilder,
    private val smartNaming: SmartNaming,
    private val storage: KobeStorage,
) {

    sealed interface Progress {
        data class Recognising(val done: Int, val total: Int) : Progress
        data class Writing(val done: Int, val total: Int) : Progress
        data object Saving : Progress
    }

    suspend operator fun invoke(
        title: String?,
        folderId: Long? = null,
        options: PdfOptions = PdfOptions(),
        runOcr: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): KobeResult<Long> {
        val state = session.current
        if (state.isEmpty) {
            return KobeResult.Failure(FailureReason.NO_DOCUMENT_DETECTED)
        }
        if (!storage.hasRoomForScan()) {
            return KobeResult.Failure(FailureReason.STORAGE_FAILED)
        }

        // 1. Recognise text. Pages already carrying OCR (recognised right after capture) are not
        //    re-run, so saving a scan the user has been reviewing for a while is near-instant.
        val ocrResults: List<OcrResult?> = if (runOcr) {
            recognise(state, onProgress)
        } else {
            List(state.pages.size) { null }
        }

        val combinedText = ocrResults.filterNotNull().joinToString("\n") { it.text }

        // 2. Decide the name. An explicit title always wins; otherwise the local heuristic reads
        //    the recognised text (SDS 40) and falls back to a timestamp.
        val suggestion = if (combinedText.isNotBlank()) smartNaming.suggest(combinedText) else null
        val resolvedTitle = FileNames.sanitise(
            title?.takeIf { it.isNotBlank() }
                ?: state.suggestedTitle
                ?: suggestion?.title
                ?: FileNames.defaultScanName(),
        )
        val type = suggestion?.type ?: state.documentType

        // 3. Write the PDF.
        val pdfFile = try {
            pdfBuilder.build(
                pages = state.pages.map { it.processedFile },
                target = storage.documentFile(FileNames.withExtension(resolvedTitle, "pdf")),
                options = options.copy(
                    metadata = options.metadata.mergedTitle(resolvedTitle),
                ),
                ocrByPage = ocrResults,
                onProgress = { done, total -> onProgress(Progress.Writing(done, total)) },
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return KobeResult.Failure(FailureReason.PDF_FAILED, t)
        }

        // 4. Record it in the library and index it for search.
        onProgress(Progress.Saving)
        val scanPages = state.pages.mapIndexed { index, page ->
            page.toScanPage(index, ocrResults.getOrNull(index)?.text ?: page.ocrText)
        }

        return try {
            val existingId = state.editingDocumentId
            val id = if (existingId != null) {
                // Editing an existing document replaces its pages and PDF rather than creating a
                // second copy; the title is only rewritten when the user actually changed it.
                repository.replacePages(existingId, scanPages)
                repository.attachPdf(existingId, pdfFile)
                if (title != null && title.isNotBlank()) repository.rename(existingId, resolvedTitle)
                if (folderId != null) repository.moveToFolder(existingId, folderId)
                existingId
            } else {
                repository.createDocument(
                    title = resolvedTitle,
                    sessionId = state.sessionId,
                    pages = scanPages,
                    folderId = folderId,
                    type = type,
                    pdfFile = pdfFile,
                    thumbnailPath = state.pages.firstOrNull()?.thumbnailFile?.absolutePath,
                )
            }
            session.clear()
            KobeResult.Success(id)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            KobeResult.Failure(FailureReason.STORAGE_FAILED, t)
        }
    }

    private suspend fun recognise(
        state: ScanSessionState,
        onProgress: (Progress) -> Unit,
    ): List<OcrResult?> {
        val total = state.pages.size
        return state.pages.mapIndexed { index, page ->
            onProgress(Progress.Recognising(index + 1, total))
            val result = ocrEngine.recognise(page.processedFile)
            if (!result.isEmpty) session.setPageOcr(page.pageId, result.text)
            result.takeIf { !it.isEmpty }
        }
    }

    private fun SessionPage.toScanPage(index: Int, ocrText: String?) = ScanPage(
        index = index,
        originalPath = originalFile.absolutePath,
        processedPath = processedFile.absolutePath,
        thumbnailPath = thumbnailFile.absolutePath,
        filter = filter,
        adjustments = adjustments,
        rotationDegrees = rotationDegrees,
        corners = quad,
        ocrText = ocrText,
        widthPx = widthPx,
        heightPx = heightPx,
    )

    private fun PdfMetadata.mergedTitle(fallback: String) =
        if (title.isBlank()) copy(title = fallback) else this
}
