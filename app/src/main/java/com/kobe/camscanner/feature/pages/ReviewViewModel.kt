package com.kobe.camscanner.feature.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.common.KobeResult
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.data.repository.ScanSessionState
import com.kobe.camscanner.data.settings.KobeSettings
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfMetadata
import com.kobe.camscanner.domain.model.PdfOptions
import com.kobe.camscanner.domain.model.PdfPageSize
import com.kobe.camscanner.domain.usecase.SaveScanSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SaveOptionsUi(
    val title: String = "",
    val folderId: Long? = null,
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val compression: PdfCompression = PdfCompression.BALANCED,
    val searchable: Boolean = true,
)

data class ReviewUiState(
    val saveOptions: SaveOptionsUi = SaveOptionsUi(),
    val showSaveSheet: Boolean = false,
    val isSaving: Boolean = false,
    val progressLabel: String = "",
    val savedDocumentId: Long? = null,
    val error: FailureReason? = null,
)

/**
 * The page editor and the save step (SDS 14, 15, 16, 17).
 *
 * The session is the source of truth for the pages themselves — this view model only owns what the
 * *save* needs, which keeps page edits made here consistent with the same edits made from the
 * camera or the filter screen.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val session: ScanSession,
    private val saveScanSession: SaveScanSession,
    private val repository: DocumentRepository,
    private val settings: KobeSettings,
) : ViewModel() {

    val sessionState: StateFlow<ScanSessionState> = session.state

    val folders: StateFlow<List<Folder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { prefs ->
                _state.update { current ->
                    current.copy(
                        saveOptions = current.saveOptions.copy(
                            pageSize = prefs.defaultPageSize,
                            compression = prefs.defaultCompression,
                            searchable = prefs.searchablePdf,
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------ page actions

    fun movePage(from: Int, to: Int) = session.movePage(from, to)
    fun rotatePage(pageId: String, delta: Int) = session.rotatePage(pageId, delta)
    fun duplicatePage(pageId: String) = session.duplicatePage(pageId)
    fun deletePage(pageId: String) = session.removePage(pageId)
    fun discard() = session.discard()

    // ------------------------------------------------------------------ save

    fun openSaveSheet() {
        val suggested = session.current.suggestedTitle ?: FileNames.defaultScanName()
        _state.update {
            it.copy(
                showSaveSheet = true,
                saveOptions = it.saveOptions.copy(
                    title = it.saveOptions.title.ifBlank { suggested },
                ),
            )
        }
    }

    fun closeSaveSheet() = _state.update { it.copy(showSaveSheet = false) }

    fun setTitle(title: String) =
        _state.update { it.copy(saveOptions = it.saveOptions.copy(title = title)) }

    fun setFolder(folderId: Long?) =
        _state.update { it.copy(saveOptions = it.saveOptions.copy(folderId = folderId)) }

    fun setPageSize(size: PdfPageSize) =
        _state.update { it.copy(saveOptions = it.saveOptions.copy(pageSize = size)) }

    fun setCompression(compression: PdfCompression) =
        _state.update { it.copy(saveOptions = it.saveOptions.copy(compression = compression)) }

    fun setSearchable(searchable: Boolean) =
        _state.update { it.copy(saveOptions = it.saveOptions.copy(searchable = searchable)) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun save() {
        val options = _state.value.saveOptions
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, showSaveSheet = false, progressLabel = "Preparing") }
            val result = saveScanSession(
                title = options.title,
                folderId = options.folderId,
                options = PdfOptions(
                    pageSize = options.pageSize,
                    compression = options.compression,
                    searchable = options.searchable,
                    metadata = PdfMetadata(title = options.title),
                ),
                onProgress = { progress ->
                    _state.update { it.copy(progressLabel = progress.label()) }
                },
            )
            when (result) {
                is KobeResult.Success -> _state.update {
                    it.copy(isSaving = false, savedDocumentId = result.value)
                }
                is KobeResult.Failure -> _state.update {
                    it.copy(isSaving = false, error = result.reason)
                }
            }
        }
    }

    fun consumeSavedId() = _state.update { it.copy(savedDocumentId = null) }

    private fun SaveScanSession.Progress.label(): String = when (this) {
        is SaveScanSession.Progress.Recognising -> "Recognising text $done of $total"
        is SaveScanSession.Progress.Writing -> "Writing page $done of $total"
        SaveScanSession.Progress.Saving -> "Saving to your library"
    }
}
