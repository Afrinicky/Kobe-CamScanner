package com.kobe.camscanner.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.repository.DocumentRepository
import com.kobe.camscanner.data.settings.KobeSettings
import com.kobe.camscanner.data.settings.Settings
import com.kobe.camscanner.data.settings.ThemePreference
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfPageSize
import com.kobe.camscanner.domain.model.ScanFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StorageUsage(val usedBytes: Long = 0L, val freeBytes: Long = 0L)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: KobeSettings,
    private val repository: DocumentRepository,
    private val storage: KobeStorage,
) : ViewModel() {

    val settings: StateFlow<Settings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _storageUsage = MutableStateFlow(StorageUsage())
    val storageUsage: StateFlow<StorageUsage> = _storageUsage.asStateFlow()

    init {
        refreshStorage()
    }

    fun setAutoCapture(value: Boolean) = viewModelScope.launch { store.setAutoCapture(value) }
    fun setAutoOcr(value: Boolean) = viewModelScope.launch { store.setAutoOcr(value) }
    fun setShowGrid(value: Boolean) = viewModelScope.launch { store.setShowGrid(value) }
    fun setSearchablePdf(value: Boolean) = viewModelScope.launch { store.setSearchablePdf(value) }
    fun setSmartNaming(value: Boolean) = viewModelScope.launch { store.setSmartNaming(value) }
    fun setAppLock(value: Boolean) = viewModelScope.launch { store.setAppLock(value) }

    fun setDefaultFilter(value: ScanFilter) = viewModelScope.launch { store.setDefaultFilter(value) }
    fun setPageSize(value: PdfPageSize) = viewModelScope.launch { store.setPageSize(value) }
    fun setCompression(value: PdfCompression) = viewModelScope.launch { store.setCompression(value) }
    fun setTheme(value: ThemePreference) = viewModelScope.launch { store.setTheme(value) }

    fun clearShareCache() = viewModelScope.launch {
        storage.clearShareCache()
        refreshStorage()
    }

    /** Permanently removes everything currently in the trash. */
    fun emptyTrash() = viewModelScope.launch {
        val trashed = repository.observeTrash().first()
        if (trashed.isNotEmpty()) repository.deleteForever(trashed.map { it.id })
        refreshStorage()
    }

    private fun refreshStorage() = viewModelScope.launch {
        _storageUsage.value = StorageUsage(
            usedBytes = storage.usedBytes(),
            freeBytes = storage.freeBytes(),
        )
    }
}
