package com.kobe.camscanner.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.camscanner.core.ui.components.EmptyState
import com.kobe.camscanner.core.ui.components.KobeSearchField
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.feature.home.DocumentRow

/**
 * Local search (SDS 25).
 *
 * The result subtitle is the matched OCR snippet rather than the file's metadata, because when a
 * user searches for "invoice 00124" what they need to see is the line of the document that
 * contains it — that is what tells them they have found the right scan without opening it.
 */
@Composable
fun SearchScreen(
    onOpenDocument: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val extra = KobeTheme.extra

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KobeTopBar(title = "Search", compact = true, onBack = onBack)

            KobeSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                autoFocus = true,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(12.dp))

            when {
                state.query.isBlank() -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = "Search your documents",
                            message = "Kobe looks through filenames and the text it recognised " +
                                "inside every scan. All of it happens on this phone.",
                        )
                    }
                }

                state.results.isEmpty() && !state.isSearching -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        EmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = "No matches",
                            message = "Nothing here contains \"${state.query}\". Try a shorter term.",
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Text(
                                text = if (state.results.size == 1) "1 result"
                                else "${state.results.size} results",
                                style = MaterialTheme.typography.labelMedium,
                                color = extra.inkMuted,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        items(state.results, key = { it.document.id }) { hit ->
                            DocumentRow(
                                document = hit.document,
                                subtitle = hit.snippet?.replace('\n', ' '),
                                onClick = { onOpenDocument(hit.document.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
