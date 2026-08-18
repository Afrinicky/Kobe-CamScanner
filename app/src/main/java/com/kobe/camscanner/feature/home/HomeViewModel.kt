package com.kobe.camscanner.feature.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.data.repository.ScanSession
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.domain.model.ScanMode
import com.kobe.camscanner.scanner.ScanProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DocumentRepository,
    private val session: ScanSession,
    private val processor: ScanProcessor,
) : ViewModel() {

    val recent: StateFlow<List<ScanDocument>> = repository.observeRecent(limit = 10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Folder>> = repository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val documentCount: StateFlow<Int> = repository.observeDocumentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedDefaultFoldersIfEmpty()
            // Housekeeping on launch rather than on a schedule: it is the one moment the user is
            // guaranteed to be waiting anyway, and it keeps the trash from growing unbounded.
            repository.purgeExpiredTrash()
        }
    }

    /** Starts a scan session for a given mode before the camera opens. */
    fun beginScan(mode: ScanMode = ScanMode.BATCH) = session.start(mode)

    /**
     * Imports pictures straight from the home screen (SDS 6.1 "Import button"), then hands the
     * caller a populated session to review.
     */
    fun importImages(uris: List<Uri>, onReady: () -> Unit) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            session.start(ScanMode.BATCH)
            uris.forEach { uri ->
                val processed = processor.ingestUri(session.current.sessionId, uri)
                if (processed != null) session.addPage(processed)
            }
            _importing.value = false
            if (!session.current.isEmpty) onReady()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch { repository.createFolder(name) }
    }
}
