package com.kobe.camscanner.feature.annotate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.common.DefaultDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.feature.signature.InkStroke
import com.kobe.camscanner.feature.signature.renderStrokesToBitmap
import com.kobe.camscanner.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class AnnotateUiState(
    val pageFile: File? = null,
    val strokes: List<InkStroke> = emptyList(),
    val isApplying: Boolean = false,
)

/**
 * Annotation state and the flatten step.
 *
 * Strokes stay vectors while the user is drawing and are only burned into the page on Apply. That
 * ordering is what lets undo be exact, and it means the ink is rasterised once, at the page's real
 * resolution, rather than repeatedly at screen resolution.
 */
@HiltViewModel
class AnnotateViewModel @Inject constructor(
    private val session: ScanSession,
    private val imageStore: ImageStore,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val pageId: String = savedStateHandle[Routes.ARG_PAGE_ID] ?: ""

    private val _state = MutableStateFlow(AnnotateUiState())
    val state: StateFlow<AnnotateUiState> = _state.asStateFlow()

    init {
        _state.value = AnnotateUiState(pageFile = session.page(pageId)?.processedFile)
    }

    fun addStroke(stroke: InkStroke) = _state.update { it.copy(strokes = it.strokes + stroke) }

    fun undo() = _state.update { it.copy(strokes = it.strokes.dropLast(1)) }

    fun clear() = _state.update { it.copy(strokes = emptyList()) }

    /**
     * Flattens the ink onto the page.
     *
     * [canvasSize] is the on-screen size of the ink layer; the strokes are scaled from it onto the
     * page's pixel dimensions, which is what keeps a mark exactly where the user put it.
     */
    fun apply(canvasSize: IntSize, onDone: () -> Unit) {
        val page = session.page(pageId)
        val strokes = _state.value.strokes
        if (page == null || strokes.isEmpty() || canvasSize.width <= 0) {
            onDone()
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isApplying = true) }
            withContext(dispatcher) {
                val base = imageStore.decodeFile(page.processedFile)
                if (base != null) {
                    val editable = base.copy(Bitmap.Config.ARGB_8888, true)
                    base.recycle()
                    if (editable != null) {
                        drawInk(editable, strokes, canvasSize)
                        imageStore.writeJpeg(editable, page.processedFile, quality = 93)
                        imageStore.writeThumbnail(editable, page.thumbnailFile)
                        editable.recycle()
                    }
                }
            }
            // The revision bump is what makes the page grid drop its cached thumbnail.
            session.bumpRevision(pageId)
            _state.update { it.copy(isApplying = false) }
            onDone()
        }
    }

    /**
     * Draws the ink layer over the page.
     *
     * The ink was drawn on a "Fit"-scaled view, so it occupies a letterboxed rectangle inside the
     * canvas rather than the whole thing. That rectangle is reconstructed here before scaling, or
     * every mark would land offset by the size of the letterbox.
     */
    private fun drawInk(page: Bitmap, strokes: List<InkStroke>, canvasSize: IntSize) {
        val pageAspect = page.width.toFloat() / page.height
        val canvasAspect = canvasSize.width.toFloat() / canvasSize.height

        val displayedWidth: Float
        val displayedHeight: Float
        if (pageAspect > canvasAspect) {
            displayedWidth = canvasSize.width.toFloat()
            displayedHeight = displayedWidth / pageAspect
        } else {
            displayedHeight = canvasSize.height.toFloat()
            displayedWidth = displayedHeight * pageAspect
        }
        val offsetX = (canvasSize.width - displayedWidth) / 2f
        val offsetY = (canvasSize.height - displayedHeight) / 2f
        val scale = page.width / displayedWidth

        val overlay = renderStrokesToBitmap(
            strokes = strokes.map { stroke ->
                stroke.copy(
                    points = stroke.points.map { point ->
                        androidx.compose.ui.geometry.Offset(
                            (point.x - offsetX).coerceIn(0f, displayedWidth),
                            (point.y - offsetY).coerceIn(0f, displayedHeight),
                        )
                    },
                )
            },
            canvasSize = IntSize(displayedWidth.toInt(), displayedHeight.toInt()),
            targetWidth = page.width,
            padding = 0f,
        ) ?: return

        // renderStrokesToBitmap trims to the ink's bounds, so the overlay is positioned back at
        // the top-left of that bounding box before compositing.
        val allPoints = strokes.flatMap { it.points }
        val minX = ((allPoints.minOf { it.x } - offsetX).coerceAtLeast(0f) * scale).toInt()
        val minY = ((allPoints.minOf { it.y } - offsetY).coerceAtLeast(0f) * scale).toInt()

        Canvas(page).drawBitmap(
            overlay,
            null,
            Rect(minX, minY, minX + overlay.width, minY + overlay.height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        overlay.recycle()
    }
}
