package com.kobe.camscanner.feature.camera

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.camera.AnalysisResult
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.settings.KobeSettings
import com.kobe.camscanner.data.settings.Settings
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.domain.model.ScanFilter
import com.kobe.camscanner.domain.model.ScanMode
import com.kobe.camscanner.ocr.OcrEngine
import com.kobe.camscanner.scanner.ScanProcessor
import com.kobe.camscanner.scanner.StabilityTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Viewfinder state that only exists while the camera is on screen.
 *
 * The grid and auto-capture overrides are nullable on purpose: a user flipping either one from the
 * viewfinder should not rewrite their saved preference, and `null` ("not overridden") has to stay
 * distinguishable from `false` ("overridden off").
 */
private data class LocalCameraState(
    val torchOn: Boolean = false,
    val zoomRatio: Float = 1f,
    val detection: StabilityTracker.State =
        StabilityTracker.State(null, StabilityTracker.Phase.SEARCHING, 0f),
    val isProcessing: Boolean = false,
    val shutterFlash: Boolean = false,
    val error: FailureReason? = null,
    val gridOverride: Boolean? = null,
    val autoCaptureOverride: Boolean? = null,
    /** width / height of the analysis frame after rotation, for mapping onto the preview. */
    val sourceAspect: Float = 3f / 4f,
)

data class CameraUiState(
    val mode: ScanMode = ScanMode.BATCH,
    val torchOn: Boolean = false,
    val gridOn: Boolean = false,
    val autoCaptureOn: Boolean = true,
    val zoomRatio: Float = 1f,
    val detection: StabilityTracker.State =
        StabilityTracker.State(null, StabilityTracker.Phase.SEARCHING, 0f),
    val isProcessing: Boolean = false,
    val shutterFlash: Boolean = false,
    val capturedCount: Int = 0,
    val lastThumbnail: File? = null,
    val error: FailureReason? = null,
    /**
     * width / height of the upright camera frame. The overlay needs it to undo the preview's
     * FILL_CENTER crop; without it the boundary is drawn at the wrong scale.
     */
    val sourceAspect: Float = 3f / 4f,
) {
    val canFinish: Boolean get() = capturedCount > 0
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val session: ScanSession,
    private val processor: ScanProcessor,
    private val ocrEngine: OcrEngine,
    private val settings: KobeSettings,
    private val storage: KobeStorage,
) : ViewModel() {

    private val local = MutableStateFlow(LocalCameraState())

    private val currentSettings: StateFlow<Settings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val uiState: StateFlow<CameraUiState> = combine(
        local,
        session.state,
        currentSettings,
    ) { local, scan, prefs ->
        CameraUiState(
            mode = scan.mode,
            torchOn = local.torchOn,
            gridOn = local.gridOverride ?: prefs.showGrid,
            autoCaptureOn = local.autoCaptureOverride ?: prefs.autoCapture,
            zoomRatio = local.zoomRatio,
            detection = local.detection,
            isProcessing = local.isProcessing,
            shutterFlash = local.shutterFlash,
            capturedCount = scan.pageCount,
            lastThumbnail = scan.pages.lastOrNull()?.thumbnailFile,
            error = local.error,
            sourceAspect = local.sourceAspect,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraUiState())

    /** Starts a fresh scan unless the user is returning to one already in progress. */
    fun startSessionIfNeeded(mode: ScanMode = ScanMode.BATCH) {
        if (session.current.isEmpty) session.start(mode)
    }

    fun onAnalysis(result: AnalysisResult) = local.update {
        it.copy(detection = result.state, sourceAspect = result.uprightAspect)
    }

    fun setMode(mode: ScanMode) = session.setMode(mode)

    fun toggleTorch() = local.update { it.copy(torchOn = !it.torchOn) }

    fun toggleGrid() = local.update {
        it.copy(gridOverride = !(it.gridOverride ?: currentSettings.value.showGrid))
    }

    fun toggleAutoCapture() = local.update {
        it.copy(autoCaptureOverride = !(it.autoCaptureOverride ?: currentSettings.value.autoCapture))
    }

    fun setZoom(ratio: Float) = local.update { it.copy(zoomRatio = ratio.coerceIn(1f, MAX_ZOOM)) }

    fun clearError() = local.update { it.copy(error = null) }

    /** Handles a completed capture: process, store, and optionally recognise text right away. */
    fun onCaptured(file: File, quad: Quad?) {
        viewModelScope.launch {
            local.update { it.copy(isProcessing = true, shutterFlash = true) }
            val processed = processor.ingestCapture(
                sessionId = session.current.sessionId,
                capturedFile = file,
                suggestedQuad = quad,
                filter = currentSettings.value.defaultFilter,
            )
            if (processed == null) {
                local.update {
                    it.copy(
                        isProcessing = false,
                        shutterFlash = false,
                        error = FailureReason.NO_DOCUMENT_DETECTED,
                    )
                }
                return@launch
            }
            val page = session.addPage(processed)
            local.update { it.copy(isProcessing = false, shutterFlash = false) }
            recogniseIfEnabled(page.pageId, page.processedFile)
        }
    }

    fun onCaptureFailed() {
        local.update { it.copy(isProcessing = false, shutterFlash = false, error = FailureReason.CAMERA_UNAVAILABLE) }
    }

    /** Imports gallery images through the identical pipeline (SDS 26). */
    fun importImages(uris: List<Uri>, onFinished: () -> Unit = {}) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            local.update { it.copy(isProcessing = true) }
            uris.forEach { uri ->
                val processed = processor.ingestUri(
                    sessionId = session.current.sessionId,
                    uri = uri,
                    filter = currentSettings.value.defaultFilter,
                )
                if (processed != null) {
                    val page = session.addPage(processed)
                    recogniseIfEnabled(page.pageId, page.processedFile)
                }
            }
            local.update { it.copy(isProcessing = false) }
            onFinished()
        }
    }

    fun discardSession() = session.discard()

    /** Where CameraX should write the next full-resolution frame. */
    fun nextCaptureTarget(): File =
        File(storage.pageDir(session.current.sessionId), "capture-${System.currentTimeMillis()}.jpg")

    /**
     * Running OCR as each page lands is what makes saving feel instant: by the time the user has
     * framed the next page, the previous one already carries its text.
     */
    private suspend fun recogniseIfEnabled(pageId: String, file: File) {
        if (!currentSettings.value.autoOcr) return
        val result = ocrEngine.recognise(file)
        if (!result.isEmpty) session.setPageOcr(pageId, result.text)
    }

    val defaultFilter: ScanFilter get() = currentSettings.value.defaultFilter

    companion object {
        const val MAX_ZOOM = 8f
    }
}
