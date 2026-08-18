package com.kobe.camscanner.feature.pages

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid], implemented
 * against the grid's own layout info (SDS 15: "Long-press: drag to reorder").
 *
 * The approach: a long press picks up the item under the finger, each drag delta is accumulated,
 * and whenever the finger's absolute position falls inside a *different* item's bounds, the two
 * swap immediately. Swapping during the drag rather than on release is what makes the surrounding
 * tiles animate out of the way, which is the feedback that tells a user the gesture is working.
 */
class ReorderState internal constructor(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
    private val onMove: (from: Int, to: Int) -> Unit,
) {
    var draggingIndex by mutableIntStateOf(-1)
        private set

    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    private var startPosition = Offset.Zero

    val isDragging: Boolean get() = draggingIndex >= 0

    internal fun onDragStart(position: Offset) {
        val item = itemAt(position) ?: return
        draggingIndex = item.index
        startPosition = position
        dragOffset = Offset.Zero
    }

    internal fun onDrag(delta: Offset) {
        if (draggingIndex < 0) return
        dragOffset += delta

        val current = startPosition + dragOffset
        val target = itemAt(current) ?: return
        if (target.index != draggingIndex) {
            onMove(draggingIndex, target.index)
            // Re-anchor to the new slot so the tile keeps tracking the finger rather than
            // snapping back by the width of one cell.
            startPosition = current
            dragOffset = Offset.Zero
            draggingIndex = target.index
        }
        autoScroll(current)
    }

    internal fun onDragEnd() {
        draggingIndex = -1
        dragOffset = Offset.Zero
    }

    /** Scrolls when the finger nears an edge, so a long list can be reordered end to end. */
    private fun autoScroll(position: Offset) {
        val info = gridState.layoutInfo
        val viewportHeight = info.viewportEndOffset - info.viewportStartOffset
        if (viewportHeight <= 0) return
        val edge = viewportHeight * EDGE_FRACTION
        val delta = when {
            position.y < info.viewportStartOffset + edge -> -SCROLL_STEP
            position.y > info.viewportEndOffset - edge -> SCROLL_STEP
            else -> return
        }
        scope.launch { gridState.scrollBy(delta) }
    }

    private fun itemAt(position: Offset): LazyGridItemInfo? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            position.x >= item.offset.x &&
                position.x <= item.offset.x + item.size.width &&
                position.y >= item.offset.y &&
                position.y <= item.offset.y + item.size.height
        }

    private companion object {
        const val EDGE_FRACTION = 0.15f
        const val SCROLL_STEP = 18f
    }
}

@Composable
fun rememberReorderState(
    gridState: LazyGridState,
    onMove: (from: Int, to: Int) -> Unit,
): ReorderState {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(gridState) { ReorderState(gridState, scope, onMove) }
}

/** Attaches the long-press drag gesture to the grid container. */
fun Modifier.reorderable(state: ReorderState): Modifier = this.pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.onDragStart(it) },
        onDragEnd = { state.onDragEnd() },
        onDragCancel = { state.onDragEnd() },
        onDrag = { change, delta ->
            change.consume()
            state.onDrag(delta)
        },
    )
}
