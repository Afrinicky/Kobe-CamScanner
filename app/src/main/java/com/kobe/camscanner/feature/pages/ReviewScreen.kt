package com.kobe.camscanner.feature.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kobe.camscanner.core.ui.components.EmptyState
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeMotion
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.data.repository.SessionPage

/**
 * Review and page management (SDS 14, 15).
 *
 * The grid is the document. Tapping a page selects it and reveals its tools inline rather than in a
 * menu, long-pressing picks it up to reorder, and the only permanent decision — Save — sits alone at
 * the bottom where it cannot be hit by accident.
 */
@Composable
fun ReviewScreen(
    onAddPage: () -> Unit,
    onEditCrop: (String) -> Unit,
    onEditFilter: (String) -> Unit,
    onAnnotate: (String) -> Unit,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    var selectedPageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.savedDocumentId) {
        state.savedDocumentId?.let {
            viewModel.consumeSavedId()
            onSaved(it)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KobeTopBar(
                title = "Review",
                subtitle = if (session.pageCount == 1) "1 page" else "${session.pageCount} pages",
                onBack = onBack,
                actions = {
                    KobeIconButton(
                        icon = Icons.Rounded.AddAPhoto,
                        contentDescription = "Add another page",
                        onClick = onAddPage,
                    )
                },
            )

            if (session.isEmpty) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "No pages yet",
                        message = "Capture a page or import one from your gallery to get started.",
                        action = { KobePrimaryButton(text = "Scan a page", onClick = onAddPage) },
                    )
                }
            } else {
                PageGrid(
                    pages = session.pages,
                    selectedPageId = selectedPageId,
                    onSelect = { selectedPageId = if (selectedPageId == it) null else it },
                    onMove = viewModel::movePage,
                    onCrop = onEditCrop,
                    onFilter = onEditFilter,
                    onAnnotate = onAnnotate,
                    onRotate = { viewModel.rotatePage(it, 90) },
                    onDuplicate = viewModel::duplicatePage,
                    onDelete = { id ->
                        if (selectedPageId == id) selectedPageId = null
                        viewModel.deletePage(id)
                    },
                    modifier = Modifier.weight(1f),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    KobePrimaryButton(
                        text = "Save document",
                        onClick = viewModel::openSaveSheet,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.showSaveSheet) {
            SaveDocumentSheet(
                options = state.saveOptions,
                folders = folders,
                pageCount = session.pageCount,
                onTitleChange = viewModel::setTitle,
                onFolderChange = viewModel::setFolder,
                onPageSizeChange = viewModel::setPageSize,
                onCompressionChange = viewModel::setCompression,
                onSearchableChange = viewModel::setSearchable,
                onDismiss = viewModel::closeSaveSheet,
                onConfirm = viewModel::save,
            )
        }

        KobeProgressVeil(
            visible = state.isSaving,
            label = "Creating your PDF",
            detail = state.progressLabel,
        )
    }
}

@Composable
private fun PageGrid(
    pages: List<SessionPage>,
    selectedPageId: String?,
    onSelect: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onCrop: (String) -> Unit,
    onFilter: (String) -> Unit,
    onAnnotate: (String) -> Unit,
    onRotate: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val reorder = rememberReorderState(gridState, onMove)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .reorderable(reorder),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(pages, key = { _, page -> page.pageId }) { index, page ->
            val isDragging = reorder.draggingIndex == index
            PageTile(
                page = page,
                number = index + 1,
                selected = page.pageId == selectedPageId,
                dragging = isDragging,
                onClick = { onSelect(page.pageId) },
                onCrop = { onCrop(page.pageId) },
                onFilter = { onFilter(page.pageId) },
                onAnnotate = { onAnnotate(page.pageId) },
                onRotate = { onRotate(page.pageId) },
                onDuplicate = { onDuplicate(page.pageId) },
                onDelete = { onDelete(page.pageId) },
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .then(
                        if (isDragging) {
                            Modifier.dragOffset(reorder.dragOffset)
                        } else {
                            Modifier.animateItem()
                        },
                    ),
            )
        }
    }
}

/** Follows the finger during a reorder drag without disturbing the grid's own layout. */
private fun Modifier.dragOffset(offset: Offset): Modifier = graphicsLayer {
    translationX = offset.x
    translationY = offset.y
}

@Composable
private fun PageTile(
    page: SessionPage,
    number: Int,
    selected: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onCrop: () -> Unit,
    onFilter: () -> Unit,
    onAnnotate: () -> Unit,
    onRotate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = KobeTheme.extra
    val scale by animateFloatAsState(
        targetValue = if (dragging) 1.06f else 1f,
        animationSpec = KobeMotion.snappySpring(),
        label = "pageTileScale",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .semantics { contentDescription = "Page $number" },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .shadow(if (dragging) 14.dp else 3.dp, KobeRadius.tile)
                .clip(KobeRadius.tile)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else extra.hairline,
                    shape = KobeRadius.tile,
                )
                .clickable(onClick = onClick),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(page.thumbnailFile)
                    // The revision key is what makes a re-rendered page refresh instead of showing
                    // the cached bitmap from before the edit.
                    .memoryCacheKey("${page.pageId}-${page.revision}")
                    .diskCacheKey("${page.pageId}-${page.revision}")
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }

            if (dragging) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (selected) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                KobeIconButton(
                    icon = Icons.Rounded.Crop,
                    contentDescription = "Crop page $number",
                    onClick = onCrop,
                    boxSize = 34.dp,
                )
                KobeIconButton(
                    icon = Icons.Rounded.Tune,
                    contentDescription = "Enhance page $number",
                    onClick = onFilter,
                    boxSize = 34.dp,
                )
                KobeIconButton(
                    icon = Icons.Rounded.Draw,
                    contentDescription = "Annotate page $number",
                    onClick = onAnnotate,
                    boxSize = 34.dp,
                )
                KobeIconButton(
                    icon = Icons.Rounded.RotateRight,
                    contentDescription = "Rotate page $number",
                    onClick = onRotate,
                    boxSize = 34.dp,
                )
                KobeIconButton(
                    icon = Icons.Rounded.ContentCopy,
                    contentDescription = "Duplicate page $number",
                    onClick = onDuplicate,
                    boxSize = 34.dp,
                )
                KobeIconButton(
                    icon = Icons.Rounded.Delete,
                    contentDescription = "Delete page $number",
                    onClick = onDelete,
                    boxSize = 34.dp,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = page.filter.label,
                style = MaterialTheme.typography.labelSmall,
                color = extra.inkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.9f),
            )
        }
    }
}
