package com.kobe.camscanner.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.camscanner.core.ui.components.EmptyState
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.feature.home.DocumentRow
import com.kobe.camscanner.feature.home.DocumentTile

/**
 * The library (SDS 22).
 *
 * One screen serves the whole library, a folder, the starred set and the trash, because they are
 * the same list under different filters — and a user who learns the controls once should not have
 * to relearn them in a second place.
 */
@Composable
fun LibraryScreen(
    onOpenDocument: (Long) -> Unit,
    onBack: () -> Unit,
    onScan: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()

    var sortMenuOpen by remember { mutableStateOf(false) }
    var moveMenuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KobeTopBar(
                title = state.folderName ?: "Library",
                subtitle = if (documents.isEmpty()) null
                else "${documents.size} " + if (documents.size == 1) "document" else "documents",
                onBack = onBack,
                actions = {
                    Box {
                        KobeIconButton(
                            icon = Icons.AutoMirrored.Rounded.Sort,
                            contentDescription = "Sort",
                            onClick = { sortMenuOpen = true },
                        )
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            LibrarySort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    KobeIconButton(
                        icon = if (state.gridMode) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                        contentDescription = if (state.gridMode) "Show as list" else "Show as grid",
                        onClick = viewModel::toggleGridMode,
                    )
                },
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LibraryScope.entries.toList()) { scope ->
                    KobeChip(
                        label = scope.label,
                        selected = state.scope == scope,
                        onClick = { viewModel.setScope(scope) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (documents.isEmpty()) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = emptyTitle(state.scope),
                        message = emptyMessage(state.scope),
                        action = if (state.scope == LibraryScope.ALL) {
                            { KobePrimaryButton(text = "Scan a document", onClick = onScan) }
                        } else {
                            null
                        },
                    )
                }
            } else if (state.gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 152.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(documents, key = { it.id }) { document ->
                        DocumentTile(
                            document = document,
                            selected = document.id in state.selection,
                            onClick = {
                                if (state.inSelectionMode) viewModel.toggleSelection(document.id)
                                else onOpenDocument(document.id)
                            },
                            onLongClick = { viewModel.toggleSelection(document.id) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(documents, key = { it.id }) { document ->
                        DocumentRow(
                            document = document,
                            onClick = {
                                if (state.inSelectionMode) viewModel.toggleSelection(document.id)
                                else onOpenDocument(document.id)
                            },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.inSelectionMode,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SelectionBar(
                count = state.selection.size,
                inTrash = state.scope == LibraryScope.TRASH,
                onClear = viewModel::clearSelection,
                onStar = { viewModel.starSelection(true) },
                onMove = { moveMenuOpen = true },
                onTrash = viewModel::trashSelection,
                onRestore = viewModel::restoreSelection,
                onDeleteForever = viewModel::deleteSelectionForever,
                moveMenu = {
                    DropdownMenu(
                        expanded = moveMenuOpen,
                        onDismissRequest = { moveMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("No folder") },
                            onClick = {
                                viewModel.moveSelectionToFolder(null)
                                moveMenuOpen = false
                            },
                        )
                        folders.forEach { folder ->
                            DropdownMenuItem(
                                text = { Text(folder.name) },
                                onClick = {
                                    viewModel.moveSelectionToFolder(folder.id)
                                    moveMenuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * The contextual action bar. It replaces nothing — it slides in beneath the list — so the user can
 * still see what they have selected while deciding what to do with it.
 */
@Composable
private fun SelectionBar(
    count: Int,
    inTrash: Boolean,
    onClear: () -> Unit,
    onStar: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    moveMenu: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .navigationBarsPadding()
            .clip(KobeRadius.card)
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KobeIconButton(
            icon = Icons.Rounded.Close,
            contentDescription = "Clear selection",
            onClick = onClear,
            container = Color.Transparent,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
        )
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.weight(1f),
        )
        if (inTrash) {
            KobeIconButton(
                icon = Icons.Rounded.Restore,
                contentDescription = "Restore",
                onClick = onRestore,
                container = Color.Transparent,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
            KobeIconButton(
                icon = Icons.Rounded.DeleteForever,
                contentDescription = "Delete permanently",
                onClick = onDeleteForever,
                container = Color.Transparent,
                tint = MaterialTheme.colorScheme.error,
            )
        } else {
            KobeIconButton(
                icon = Icons.Rounded.Star,
                contentDescription = "Add star",
                onClick = onStar,
                container = Color.Transparent,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
            Box {
                KobeIconButton(
                    icon = Icons.Rounded.DriveFileMove,
                    contentDescription = "Move to folder",
                    onClick = onMove,
                    container = Color.Transparent,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                )
                moveMenu()
            }
            KobeIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = "Move to trash",
                onClick = onTrash,
                container = Color.Transparent,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }
}

private fun emptyTitle(scope: LibraryScope) = when (scope) {
    LibraryScope.ALL -> "Your library is empty"
    LibraryScope.FAVOURITES -> "Nothing starred"
    LibraryScope.TRASH -> "Trash is empty"
}

private fun emptyMessage(scope: LibraryScope) = when (scope) {
    LibraryScope.ALL -> "Scanned documents appear here, stored only on this phone."
    LibraryScope.FAVOURITES -> "Star a document to keep it within reach."
    LibraryScope.TRASH -> "Deleted documents wait here for 30 days before they are removed."
}
