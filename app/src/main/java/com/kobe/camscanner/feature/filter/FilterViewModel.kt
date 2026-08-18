package com.kobe.camscanner.feature.filter

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.repository.SessionPage
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.ScanFilter
import com.kobe.camscanner.navigation.Routes
import com.kobe.camscanner.scanner.ScanProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilterUiState(
    val page: SessionPage? = null,
    val filter: ScanFilter = ScanFilter.AUTO,
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val previews: Map<ScanFilter, Bitmap> = emptyMap(),
    val livePreview: Bitmap? = null,
    val isApplying: Boolean = false,
    val showAdjustments: Boolean = false,
)

/**
 * Filter and enhancement controls (SDS 12).
 *
 * Two preview tiers keep this responsive. The filter strip renders eight thumbnails once, at
 * [ScanProcessor.PREVIEW_EDGE]; the main preview re-renders at a medium size and only after the
 * slider has been still for [DEBOUNCE_MS], so dragging a slider does not queue a render per frame.
 * The full-resolution page is only rendered when the user applies.
 */
@HiltViewModel
class FilterViewModel @Inject constructor(
    private val session: ScanSession,
    private val processor: ScanProcessor,
    private val imageStore: ImageStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pageId: String = savedStateHandle[Routes.ARG_PAGE_ID] ?: ""

    private val _state = MutableStateFlow(FilterUiState())
    val state: StateFlow<FilterUiState> = _state.asStateFlow()

    /** The corrected page before any filter, kept in memory to re-render previews from. */
    private var baseBitmap: Bitmap? = null
    private var previewJob: Job? = null

    init {
        val page = session.page(pageId)
        _state.value = FilterUiState(
            page = page,
            filter = page?.filter ?: ScanFilter.AUTO,
            adjustments = page?.adjustments ?: Adjustments.NEUTRAL,
        )
        if (page != null) loadPreviews(page)
    }

    private fun loadPreviews(page: SessionPage) {
        viewModelScope.launch {
            // Perspective correction has already happened, so the stored page is the right base
            // for a filter comparison; re-warping the original here would be wasted work.
            val base = imageStore.decodeFile(page.processedFile, PREVIEW_BASE_EDGE) ?: return@launch
            baseBitmap = base

            val previews = LinkedHashMap<ScanFilter, Bitmap>()
            ScanFilter.entries.forEach { filter ->
                previews[filter] = processor.filterPreview(base, filter)
                // Publish as each one finishes so the strip fills in rather than appearing at once.
                _state.update { it.copy(previews = LinkedHashMap(previews)) }
            }
            renderLivePreview()
        }
    }

    fun selectFilter(filter: ScanFilter) {
        _state.update { it.copy(filter = filter) }
        renderLivePreview()
    }

    fun setAdjustments(adjustments: Adjustments) {
        _state.update { it.copy(adjustments = adjustments) }
        renderLivePreview(debounce = true)
    }

    fun resetAdjustments() {
        _state.update { it.copy(adjustments = Adjustments.NEUTRAL) }
        renderLivePreview()
    }

    fun toggleAdjustments() = _state.update { it.copy(showAdjustments = !it.showAdjustments) }

    fun rotate(delta: Int) {
        val page = _state.value.page ?: return
        session.rotatePage(page.pageId, delta)
        _state.update { it.copy(page = session.page(page.pageId)) }
    }

    /** Renders at full resolution and writes the page back to the session. */
    fun apply(onDone: () -> Unit) {
        val page = _state.value.page ?: return onDone()
        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            val processed = processor.reprocess(
                sessionId = session.current.sessionId,
                pageId = page.pageId,
                originalFile = page.originalFile,
                quad = page.quad,
                filter = _state.value.filter,
                adjustments = _state.value.adjustments,
                rotationDegrees = session.page(page.pageId)?.rotationDegrees ?: page.rotationDegrees,
            )
            if (processed != null) session.replacePage(page.pageId, processed)
            _state.update { it.copy(isApplying = false) }
            onDone()
        }
    }

    private fun renderLivePreview(debounce: Boolean = false) {
        val base = baseBitmap ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val bitmap = processor.filterPreview(
                source = base,
                filter = _state.value.filter,
                adjustments = _state.value.adjustments,
                // The main preview is the one the user judges the scan by, so it renders at the
                // full base size rather than at thumbnail scale.
                maxEdge = PREVIEW_BASE_EDGE,
            )
            _state.update { it.copy(livePreview = bitmap) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        baseBitmap?.recycle()
        baseBitmap = null
    }

    private companion object {
        /** Big enough to judge sharpness on, small enough to re-render while a slider moves. */
        const val PREVIEW_BASE_EDGE = 900
        const val DEBOUNCE_MS = 120L
    }
}
