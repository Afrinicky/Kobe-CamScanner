package com.kobe.camscanner.feature.crop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.repository.SessionPage
import com.kobe.camscanner.domain.model.PointN
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.navigation.Routes
import com.kobe.camscanner.scanner.ScanProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class CropUiState(
    val page: SessionPage? = null,
    val originalFile: File? = null,
    val quad: Quad = Quad.FULL,
    val detectedQuad: Quad? = null,
    val isApplying: Boolean = false,
    val isDirty: Boolean = false,
)

/**
 * Manual corner adjustment (SDS 10).
 *
 * The screen always works from the *original* capture, never from the already-corrected result, so
 * a user who over-cropped can drag the corners back out. Applying re-runs the whole render, which
 * is why Reset and Cancel are both cheap and both offered.
 */
@HiltViewModel
class CropViewModel @Inject constructor(
    private val session: ScanSession,
    private val processor: ScanProcessor,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pageId: String = savedStateHandle[Routes.ARG_PAGE_ID] ?: ""

    private val _state = MutableStateFlow(CropUiState())
    val state: StateFlow<CropUiState> = _state.asStateFlow()

    init {
        val page = session.page(pageId)
        _state.value = CropUiState(
            page = page,
            originalFile = page?.originalFile,
            quad = page?.quad ?: Quad.FULL,
            detectedQuad = page?.quad,
        )
        // If the page was captured without a boundary, look for one now rather than making the
        // user drag four corners from the frame edges.
        if (page != null && page.quad == null) {
            viewModelScope.launch {
                val detected = processor.detectBoundary(page.originalFile)
                if (detected != null) {
                    _state.update { it.copy(quad = detected, detectedQuad = detected) }
                }
            }
        }
    }

    /** Moves one corner. [index] follows [Quad.points]: 0 = TL, 1 = TR, 2 = BR, 3 = BL. */
    fun moveCorner(index: Int, position: PointN) {
        _state.update { state ->
            val points = state.quad.points.toMutableList()
            if (index !in points.indices) return@update state
            points[index] = position.clamped()
            state.copy(
                quad = Quad(points[0], points[1], points[2], points[3]),
                isDirty = true,
            )
        }
    }

    /** Back to what the detector found, or to the whole frame if it found nothing. */
    fun reset() {
        _state.update { it.copy(quad = it.detectedQuad ?: Quad.FULL, isDirty = true) }
    }

    fun selectWholePage() {
        _state.update { it.copy(quad = Quad.FULL, isDirty = true) }
    }

    fun apply(onDone: () -> Unit) {
        val page = _state.value.page ?: return onDone()
        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            val processed = processor.reprocess(
                sessionId = session.current.sessionId,
                pageId = page.pageId,
                originalFile = page.originalFile,
                quad = _state.value.quad,
                filter = page.filter,
                adjustments = page.adjustments,
                rotationDegrees = page.rotationDegrees,
            )
            if (processed != null) session.replacePage(page.pageId, processed)
            _state.update { it.copy(isApplying = false) }
            onDone()
        }
    }
}
