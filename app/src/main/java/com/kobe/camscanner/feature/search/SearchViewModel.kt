package com.kobe.camscanner.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kobe.camscanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<DocumentRepository.SearchHit> = emptyList(),
    val isSearching: Boolean = false,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: DocumentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    @OptIn(FlowPreview::class)
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            queries
                .map { it.trim() }
                .distinctUntilChanged()
                // Search runs against a local FTS index, so it is fast — but debouncing still
                // avoids a query per keystroke while someone types a long phrase.
                .debounce(DEBOUNCE_MS)
                .collect { term ->
                    if (term.isEmpty()) {
                        _state.update { it.copy(results = emptyList(), isSearching = false) }
                        return@collect
                    }
                    _state.update { it.copy(isSearching = true) }
                    val hits = repository.search(term)
                    _state.update { it.copy(results = hits, isSearching = false) }
                }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queries.value = query
    }

    private companion object {
        const val DEBOUNCE_MS = 180L
    }
}
