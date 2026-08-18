package com.kobe.camscanner.feature.document

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.repository.ScanSessionState
import com.kobe.camscanner.data.repository.SessionPage
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.domain.model.ScanPage
import com.kobe.camscanner.navigation.Routes
import com.kobe.camscanner.ocr.OcrEngine
import com.kobe.camscanner.pdf.PdfBuilder
import com.kobe.camscanner.pdf.PdfTools
import com.kobe.camscanner.share.PrintHelper
import com.kobe.camscanner.share.ShareHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class DocumentUiState(
    val busyLabel: String? = null,
    val extractedText: String? = null,
    val showTextSheet: Boolean = false,
    val showRenameDialog: Boolean = false,
    val showInfoSheet: Boolean = false,
    val showShareSheet: Boolean = false,
    val message: String? = null,
    val error: FailureReason? = null,
    val whatsAppAvailable: Boolean = false,
    val closed: Boolean = false,
)

/**
 * A saved document: viewing, sharing, printing, OCR text and housekeeping (SDS 21, 27, 28, 29).
 */
@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val session: ScanSession,
    private val ocrEngine: OcrEngine,
    private val pdfBuilder: PdfBuilder,
    private val pdfTools: PdfTools,
    private val shareHelper: ShareHelper,
    private val printHelper: PrintHelper,
    private val storage: KobeStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long =
        savedStateHandle.get<String>(Routes.ARG_DOCUMENT_ID)?.toLongOrNull() ?: 0L

    val document: StateFlow<ScanDocument?> = repository.observeDocument(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pages: StateFlow<List<ScanPage>> = repository.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(whatsAppAvailable = shareHelper.whatsAppPackage() != null) }
    }

    // ------------------------------------------------------------------ text

    /**
     * Shows the document's text. Pages recognised at capture time already carry it; only pages
     * that do not are put through OCR now, which is why this is instant for most documents.
     */
    fun extractText() {
        viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Reading text", showTextSheet = true) }
            val current = pages.value
            val stored = current.mapNotNull { it.ocrText?.takeIf { text -> text.isNotBlank() } }

            val text = if (stored.size == current.size && stored.isNotEmpty()) {
                stored.joinToString("\n\n")
            } else {
                current.mapIndexed { index, page ->
                    page.ocrText?.takeIf { it.isNotBlank() } ?: run {
                        _state.update { it.copy(busyLabel = "Reading page ${index + 1}") }
                        val result = ocrEngine.recognise(page.processedFile)
                        if (!result.isEmpty) repository.setPageOcr(page.id, result.text)
                        result.text
                    }
                }.joinToString("\n\n")
            }

            if (text.isBlank()) {
                _state.update {
                    it.copy(busyLabel = null, showTextSheet = false, error = FailureReason.OCR_FAILED)
                }
            } else {
                _state.update { it.copy(busyLabel = null, extractedText = text) }
            }
        }
    }

    fun closeTextSheet() = _state.update { it.copy(showTextSheet = false) }

    fun shareText() {
        val text = _state.value.extractedText ?: return
        viewModelScope.launch {
            val file = storage.shareFile(
                FileNames.withExtension(document.value?.title ?: "Scan", "txt"),
            )
            file.writeText(text)
            shareHelper.share(file, ShareHelper.MIME_TEXT, "Share text")
        }
    }

    // ------------------------------------------------------------------ share, print, export

    fun openShareSheet() = _state.update { it.copy(showShareSheet = true) }
    fun closeShareSheet() = _state.update { it.copy(showShareSheet = false) }

    fun shareToWhatsApp() = withPdf { pdf ->
        shareHelper.shareToWhatsApp(pdf)
        closeShareSheet()
    }

    fun sharePdf() = withPdf { pdf ->
        shareHelper.share(pdf)
        closeShareSheet()
    }

    fun shareImages() {
        val files = pages.value.map { it.processedFile }.filter { it.exists() }
        if (files.isEmpty()) return
        shareHelper.shareMultiple(files)
        closeShareSheet()
    }

    fun openInPdfViewer() = withPdf { pdf ->
        if (!shareHelper.openExternally(pdf)) {
            _state.update { it.copy(message = "No PDF viewer is installed on this phone.") }
        }
    }

    fun print() = withPdf { pdf ->
        if (!printHelper.print(pdf, document.value?.title ?: "Kobe scan")) {
            _state.update { it.copy(message = "Printing is not available on this device.") }
        }
        closeShareSheet()
    }

    /** Completes a "save to Files" pick by copying the PDF into the chosen location. */
    fun exportTo(destination: Uri) = withPdf { pdf ->
        val ok = shareHelper.copyToUri(pdf, destination)
        _state.update {
            it.copy(message = if (ok) "Saved to your chosen folder." else null, showShareSheet = false)
        }
        if (!ok) _state.update { it.copy(error = FailureReason.STORAGE_FAILED) }
    }

    fun suggestedFileName(): String =
        FileNames.withExtension(document.value?.title ?: "Kobe scan", "pdf")

    /**
     * Loads this document's pages into the scan session and hands control to the page editor.
     *
     * Editing a saved document and building a new one are the same operation on the same state;
     * the session simply remembers which document it came from so Save updates rather than
     * duplicates.
     */
    fun openInPageEditor(onReady: () -> Unit) {
        viewModelScope.launch {
            val doc = document.value ?: return@launch
            val current = pages.value
            if (current.isEmpty()) return@launch
            session.resume(
                ScanSessionState(
                    sessionId = doc.sessionIdOrFallback(),
                    pages = current.map { it.toSessionPage() },
                    suggestedTitle = doc.title,
                    documentType = doc.type,
                    editingDocumentId = doc.id,
                ),
            )
            onReady()
        }
    }

    private fun ScanDocument.sessionIdOrFallback(): String = "doc-$id"

    private fun ScanPage.toSessionPage() = SessionPage(
        pageId = id.toString(),
        originalFile = originalFile,
        processedFile = processedFile,
        thumbnailFile = thumbnailPath?.let(::File) ?: processedFile,
        quad = corners,
        filter = filter,
        adjustments = adjustments,
        rotationDegrees = rotationDegrees,
        widthPx = widthPx,
        heightPx = heightPx,
        ocrText = ocrText,
    )

    // ------------------------------------------------------------------ document management

    fun openRenameDialog() = _state.update { it.copy(showRenameDialog = true) }
    fun closeRenameDialog() = _state.update { it.copy(showRenameDialog = false) }
    fun openInfoSheet() = _state.update { it.copy(showInfoSheet = true) }
    fun closeInfoSheet() = _state.update { it.copy(showInfoSheet = false) }
    fun clearMessage() = _state.update { it.copy(message = null, error = null) }

    fun rename(title: String) {
        viewModelScope.launch {
            repository.rename(documentId, title)
            _state.update { it.copy(showRenameDialog = false) }
        }
    }

    fun moveToFolder(folderId: Long?) {
        viewModelScope.launch { repository.moveToFolder(documentId, folderId) }
    }

    fun toggleFavourite() {
        val current = document.value ?: return
        viewModelScope.launch { repository.setFavourite(documentId, !current.isFavourite) }
    }

    fun moveToTrash() {
        viewModelScope.launch {
            repository.moveToTrash(listOf(documentId))
            _state.update { it.copy(closed = true) }
        }
    }

    /** Rewrites the PDF at a smaller raster size (SDS 18). */
    fun compress() = withPdf { pdf ->
        _state.update { it.copy(busyLabel = "Compressing") }
        val target = storage.documentFile(FileNames.withExtension(pdf.nameWithoutExtension, "pdf"))
        val temp = File(target.parentFile, target.nameWithoutExtension + "-compressed.pdf")
        runCatching {
            pdfTools.compress(
                source = pdf,
                target = temp,
                builder = pdfBuilder,
                options = PdfOptions(compression = PdfCompression.SMALL, searchable = false),
            )
        }.onSuccess {
            val before = pdf.length()
            temp.copyTo(target, overwrite = true)
            temp.delete()
            repository.attachPdf(documentId, target)
            val saved = (before - target.length()).coerceAtLeast(0)
            _state.update {
                it.copy(
                    busyLabel = null,
                    message = "Compressed. Saved " +
                        com.kobe.camscanner.core.common.Formatting.fileSize(saved) + ".",
                )
            }
        }.onFailure {
            temp.delete()
            _state.update { it.copy(busyLabel = null, error = FailureReason.PDF_FAILED) }
        }
    }

    /**
     * Rebuilds a document's PDF if the file is missing — which can happen if a user cleared app
     * data for the export folder but the library row survived.
     */
    private fun withPdf(block: suspend (File) -> Unit) {
        viewModelScope.launch {
            val doc = document.value ?: return@launch
            val existing = doc.pdfFile
            val pdf = if (existing != null && existing.exists()) {
                existing
            } else {
                _state.update { it.copy(busyLabel = "Preparing PDF") }
                val target = storage.documentFile(FileNames.withExtension(doc.title, "pdf"))
                val sources = pages.value.map { it.processedFile }.filter { it.exists() }
                if (sources.isEmpty()) {
                    _state.update { it.copy(busyLabel = null, error = FailureReason.PDF_FAILED) }
                    return@launch
                }
                val built = runCatching { pdfBuilder.build(sources, target, PdfOptions()) }.getOrNull()
                if (built == null) {
                    _state.update { it.copy(busyLabel = null, error = FailureReason.PDF_FAILED) }
                    return@launch
                }
                repository.attachPdf(documentId, built)
                _state.update { it.copy(busyLabel = null) }
                built
            }
            block(pdf)
        }
    }
}
