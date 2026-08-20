package com.kobe.camscanner.feature.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.domain.model.DocumentType
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.domain.model.ScanPage
import com.kobe.camscanner.pdf.PdfBuilder
import com.kobe.camscanner.pdf.PdfTools
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

/** Which multi-document operation the picker is currently collecting documents for. */
enum class PdfOperation(val title: String, val action: String, val minimum: Int) {
    MERGE("Merge PDFs", "Merge", 2),
    COMPRESS("Compress PDFs", "Compress", 1),
}

data class ToolsUiState(
    val operation: PdfOperation? = null,
    val selection: List<Long> = emptyList(),
    val busyLabel: String? = null,
    val message: String? = null,
    val error: FailureReason? = null,
    val openDocumentId: Long? = null,
) {
    val canRun: Boolean get() = operation != null && selection.size >= operation.minimum
}

/**
 * The PDF tooling behind the Tools tab.
 *
 * The merge and compress engines existed from the start but had no way in — they were listed in the
 * architecture notes as "built but not surfaced". This is the surface.
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val pdfTools: PdfTools,
    private val pdfBuilder: PdfBuilder,
    private val storage: KobeStorage,
) : ViewModel() {

    val documents: StateFlow<List<ScanDocument>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ToolsUiState())
    val state: StateFlow<ToolsUiState> = _state.asStateFlow()

    fun startOperation(operation: PdfOperation) =
        _state.update { it.copy(operation = operation, selection = emptyList()) }

    fun cancelOperation() = _state.update { it.copy(operation = null, selection = emptyList()) }

    fun toggle(documentId: Long) = _state.update { state ->
        state.copy(
            selection = if (documentId in state.selection) {
                state.selection - documentId
            } else {
                // Order matters for a merge, so selection is a list and appends in tap order.
                state.selection + documentId
            },
        )
    }

    fun clearMessage() = _state.update { it.copy(message = null, error = null) }
    fun consumeOpen() = _state.update { it.copy(openDocumentId = null) }

    fun run() {
        val state = _state.value
        when (state.operation) {
            PdfOperation.MERGE -> merge(state.selection)
            PdfOperation.COMPRESS -> compress(state.selection)
            null -> Unit
        }
    }

    /** Concatenates the chosen documents, in the order they were tapped, into a new one. */
    private fun merge(ids: List<Long>) {
        if (ids.size < 2) return
        viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Merging", operation = null) }
            val docs = ids.mapNotNull { repository.getDocument(it) }
            val sources = docs.mapNotNull { doc -> resolvePdf(doc) }

            if (sources.size < 2) {
                _state.update { it.copy(busyLabel = null, error = FailureReason.PDF_FAILED) }
                return@launch
            }

            val title = FileNames.uniquify(
                "Merged " + com.kobe.camscanner.core.common.Formatting.fileStamp(),
                emptySet(),
            )
            val target = storage.documentFile(FileNames.withExtension(title, "pdf"))

            runCatching { pdfTools.merge(sources, target) }
                .onSuccess { merged ->
                    // The merged file is the artefact; its pages are recorded by reference so the
                    // library row has thumbnails and a page count like any other document.
                    val pages = docs.flatMap { repository.getPages(it.id) }
                    val id = repository.createDocument(
                        title = title,
                        sessionId = "merge-" + System.currentTimeMillis(),
                        pages = pages.mapIndexed { index, page -> page.copy(id = 0, index = index) },
                        type = DocumentType.DOCUMENT,
                        pdfFile = merged,
                        thumbnailPath = pages.firstOrNull()?.thumbnailPath,
                    )
                    _state.update {
                        it.copy(
                            busyLabel = null,
                            selection = emptyList(),
                            message = "Merged ${docs.size} documents.",
                            openDocumentId = id,
                        )
                    }
                }
                .onFailure {
                    _state.update { s -> s.copy(busyLabel = null, error = FailureReason.PDF_FAILED) }
                }
        }
    }

    /** Rewrites each chosen document's PDF at a smaller raster size, in place. */
    private fun compress(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Compressing", operation = null) }
            var savedBytes = 0L
            var done = 0

            ids.forEach { id ->
                val doc = repository.getDocument(id) ?: return@forEach
                val source = resolvePdf(doc) ?: return@forEach
                _state.update { it.copy(busyLabel = "Compressing ${done + 1} of ${ids.size}") }

                val temp = File(source.parentFile, source.nameWithoutExtension + "-small.pdf")
                runCatching {
                    pdfTools.compress(
                        source = source,
                        target = temp,
                        builder = pdfBuilder,
                        options = PdfOptions(compression = PdfCompression.SMALL, searchable = false),
                    )
                }.onSuccess {
                    val before = source.length()
                    temp.copyTo(source, overwrite = true)
                    temp.delete()
                    repository.attachPdf(id, source)
                    savedBytes += (before - source.length()).coerceAtLeast(0)
                    done++
                }.onFailure { temp.delete() }
            }

            _state.update {
                it.copy(
                    busyLabel = null,
                    selection = emptyList(),
                    message = if (done > 0) {
                        "Compressed $done. Saved " +
                            com.kobe.camscanner.core.common.Formatting.fileSize(savedBytes) + "."
                    } else {
                        null
                    },
                    error = if (done == 0) FailureReason.PDF_FAILED else null,
                )
            }
        }
    }

    /** Returns the document's PDF, rebuilding it from the page images if the file has gone. */
    private suspend fun resolvePdf(doc: ScanDocument): File? {
        val existing = doc.pdfFile
        if (existing != null && existing.exists()) return existing

        val pages: List<ScanPage> = repository.getPages(doc.id)
        val sources = pages.map { it.processedFile }.filter { it.exists() }
        if (sources.isEmpty()) return null

        val target = storage.documentFile(FileNames.withExtension(doc.title, "pdf"))
        return runCatching { pdfBuilder.build(sources, target, PdfOptions()) }
            .onSuccess { repository.attachPdf(doc.id, it) }
            .getOrNull()
    }
}
