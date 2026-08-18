package com.kobe.camscanner.feature.watermark

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.ScanPage
import com.kobe.camscanner.navigation.Routes
import com.kobe.camscanner.pdf.PdfBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class WatermarkUiState(
    val spec: WatermarkSpec = WatermarkSpec(text = "CONFIDENTIAL"),
    val firstPageFile: File? = null,
    val preview: Bitmap? = null,
    val isApplying: Boolean = false,
    val progressLabel: String = "",
)

@HiltViewModel
class WatermarkViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val engine: WatermarkEngine,
    private val imageStore: ImageStore,
    private val pdfBuilder: PdfBuilder,
    private val storage: KobeStorage,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val documentId: Long =
        savedStateHandle.get<String>(Routes.ARG_DOCUMENT_ID)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(WatermarkUiState())
    val state: StateFlow<WatermarkUiState> = _state.asStateFlow()

    private var pages: List<ScanPage> = emptyList()
    private var previewBase: Bitmap? = null
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            pages = repository.getPages(documentId)
            val first = pages.firstOrNull()?.processedFile
            _state.update { it.copy(firstPageFile = first) }
            if (first != null) {
                previewBase = imageStore.decodeFile(first, PREVIEW_EDGE)
                renderPreview()
            }
        }
    }

    fun setText(text: String) = update { it.copy(text = text) }
    fun setOpacity(value: Float) = update { it.copy(opacity = value) }
    fun setSize(value: Float) = update { it.copy(relativeSize = value) }
    fun setRotation(value: Float) = update { it.copy(rotationDegrees = value) }
    fun setPosition(position: WatermarkPosition) = update { it.copy(position = position) }

    private fun update(transform: (WatermarkSpec) -> WatermarkSpec) {
        _state.update { it.copy(spec = transform(it.spec)) }
        renderPreview()
    }

    private fun renderPreview() {
        val base = previewBase ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            // Sliders emit continuously; a short debounce keeps the composite off the critical
            // path of the drag without the preview ever feeling behind.
            delay(PREVIEW_DEBOUNCE_MS)
            val spec = _state.value.spec
            val rendered = if (spec.text.isBlank()) base else engine.apply(base, spec)
            _state.update { it.copy(preview = rendered) }
        }
    }

    /**
     * Burns the watermark into every page and rebuilds the PDF.
     *
     * The page images are overwritten rather than copied: a watermark is a decision about the
     * document, and keeping a clean parallel copy would silently double the storage every scan
     * uses. The per-page originals are untouched, so a page can still be re-rendered from source.
     */
    fun apply(onDone: () -> Unit) {
        val spec = _state.value.spec
        if (spec.text.isBlank() || pages.isEmpty()) return onDone()

        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            pages.forEachIndexed { index, page ->
                _state.update { it.copy(progressLabel = "Page ${index + 1} of ${pages.size}") }
                engine.applyToFile(page.processedFile, page.processedFile, spec)
                page.thumbnailPath?.let { path ->
                    imageStore.decodeFile(page.processedFile, 1024)?.let { bitmap ->
                        imageStore.writeThumbnail(bitmap, File(path))
                        bitmap.recycle()
                    }
                }
            }

            _state.update { it.copy(progressLabel = "Rebuilding PDF") }
            val document = repository.getDocument(documentId)
            if (document != null) {
                val target = storage.documentFile(FileNames.withExtension(document.title, "pdf"))
                runCatching {
                    pdfBuilder.build(
                        pages = pages.map { it.processedFile },
                        target = target,
                        options = PdfOptions(searchable = false),
                    )
                }.onSuccess { repository.attachPdf(documentId, it) }
            }

            _state.update { it.copy(isApplying = false) }
            onDone()
        }
    }

    override fun onCleared() {
        super.onCleared()
        previewBase?.recycle()
        previewBase = null
    }

    private companion object {
        const val PREVIEW_EDGE = 900
        const val PREVIEW_DEBOUNCE_MS = 90L
    }
}
