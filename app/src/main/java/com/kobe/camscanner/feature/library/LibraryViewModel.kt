package com.kobe.camscanner.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryScope(val label: String) {
    ALL("All"),
    FAVOURITES("Starred"),
    TRASH("Trash"),
}

enum class LibrarySort(val label: String) {
    RECENT("Recent"),
    NAME("Name"),
    LARGEST("Largest"),
    PAGES("Most pages"),
}

data class LibraryUiState(
    val scope: LibraryScope = LibraryScope.ALL,
    val sort: LibrarySort = LibrarySort.RECENT,
    val gridMode: Boolean = true,
    val selection: Set<Long> = emptySet(),
    val folderName: String? = null,
) {
    val inSelectionMode: Boolean get() = selection.isNotEmpty()
}

/**
 * The document library (SDS 22, 23).
 *
 * Scope, sort and folder all feed one query flow, so switching between "All", a folder and the
 * trash never leaves a stale list on screen. Multi-select lives here too, which is what lets the
 * bulk actions — move, star, trash — operate on exactly what the user has ticked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: DocumentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val folderId: Long? = savedStateHandle.get<String>(Routes.ARG_FOLDER_ID)?.toLongOrNull()

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    val folders: StateFlow<List<Folder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documents: StateFlow<List<ScanDocument>> = combine(
        _state,
        _state.flatMapLatest { state -> source(state.scope) },
    ) { state, documents ->
        documents.sortedWith(comparatorFor(state.sort))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (folderId != null) {
            viewModelScope.launch {
                val name = folders.value.firstOrNull { it.id == folderId }?.name
                _state.update { it.copy(folderName = name) }
            }
        }
    }

    private fun source(scope: LibraryScope) = when {
        scope == LibraryScope.TRASH -> repository.observeTrash()
        scope == LibraryScope.FAVOURITES -> repository.observeFavourites()
        folderId != null -> repository.observeInFolder(folderId)
        else -> repository.observeAll()
    }

    private fun comparatorFor(sort: LibrarySort): Comparator<ScanDocument> = when (sort) {
        LibrarySort.RECENT -> compareByDescending { it.updatedAt }
        LibrarySort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        LibrarySort.LARGEST -> compareByDescending { it.sizeBytes }
        LibrarySort.PAGES -> compareByDescending { it.pageCount }
    }

    fun setScope(scope: LibraryScope) = _state.update { it.copy(scope = scope, selection = emptySet()) }
    fun setSort(sort: LibrarySort) = _state.update { it.copy(sort = sort) }
    fun toggleGridMode() = _state.update { it.copy(gridMode = !it.gridMode) }

    fun toggleSelection(id: Long) = _state.update { state ->
        state.copy(
            selection = if (id in state.selection) state.selection - id else state.selection + id,
        )
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun selectAll() = _state.update { it.copy(selection = documents.value.map { doc -> doc.id }.toSet()) }

    // ------------------------------------------------------------------ actions

    fun rename(id: Long, title: String) {
        viewModelScope.launch { repository.rename(id, title) }
    }

    fun moveSelectionToFolder(folderId: Long?) {
        val ids = _state.value.selection
        viewModelScope.launch {
            ids.forEach { repository.moveToFolder(it, folderId) }
            clearSelection()
        }
    }

    fun starSelection(favourite: Boolean) {
        val ids = _state.value.selection
        viewModelScope.launch {
            ids.forEach { repository.setFavourite(it, favourite) }
            clearSelection()
        }
    }

    fun trashSelection() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            repository.moveToTrash(ids)
            clearSelection()
        }
    }

    fun restoreSelection() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            repository.restoreFromTrash(ids)
            clearSelection()
        }
    }

    fun deleteSelectionForever() {
        val ids = _state.value.selection.toList()
        viewModelScope.launch {
            repository.deleteForever(ids)
            clearSelection()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }
}
