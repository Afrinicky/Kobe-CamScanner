package com.kobe.camscanner.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kobe.camscanner.R
import com.kobe.camscanner.core.ui.components.EmptyState
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeWordmark
import com.kobe.camscanner.core.ui.components.SectionHeader
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.domain.model.ScanMode

/**
 * Home (SDS 6.1).
 *
 * The screen answers one question in its first two hundred pixels — how do I scan? — and only then
 * offers the library. The SCAN action is the single gradient-filled control in the app and is placed
 * above the fold on every phone size; everything below it is recall, not action.
 */
@Composable
fun HomeScreen(
    pendingImport: List<android.net.Uri> = emptyList(),
    onImportConsumed: () -> Unit = {},
    onScan: () -> Unit,
    onReview: () -> Unit,
    onOpenDocument: (Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFolder: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val documentCount by viewModel.documentCount.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMPORT),
    ) { uris -> viewModel.importImages(uris) { onReview() } }

    LaunchedEffect(pendingImport) {
        if (pendingImport.isNotEmpty()) {
            viewModel.importImages(pendingImport) { onReview() }
            onImportConsumed()
        }
    }

    val startScan: (ScanMode) -> Unit = { mode ->
        viewModel.beginScan(mode)
        onScan()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 24.dp, end = 12.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KobeWordmark(showTagline = true, modifier = Modifier.weight(1f))
                    KobeIconButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        onClick = onOpenSettings,
                        container = Color.Transparent,
                    )
                }
            }

            item {
                SearchEntry(
                    onClick = onOpenSearch,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                    KobePrimaryButton(
                        text = "SCAN",
                        icon = Icons.Rounded.DocumentScanner,
                        onClick = { startScan(ScanMode.BATCH) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickAction(
                            icon = Icons.Rounded.PhotoLibrary,
                            label = "Import",
                            onClick = {
                                importLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                        QuickAction(
                            icon = Icons.Rounded.Badge,
                            label = "ID card",
                            onClick = { startScan(ScanMode.ID_CARD) },
                            modifier = Modifier.weight(1f),
                        )
                        QuickAction(
                            icon = Icons.Rounded.ReceiptLong,
                            label = "Receipt",
                            onClick = { startScan(ScanMode.RECEIPT) },
                            modifier = Modifier.weight(1f),
                        )
                        QuickAction(
                            icon = Icons.AutoMirrored.Rounded.MenuBook,
                            label = "Book",
                            onClick = { startScan(ScanMode.BOOK) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            if (folders.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(28.dp))
                    SectionHeader(
                        title = "Folders",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            FolderChipCard(folder = folder, onClick = { onOpenFolder(folder.id) })
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                SectionHeader(
                    title = "Recent",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    trailing = {
                        if (documentCount > recent.size) {
                            Text(
                                text = "See all $documentCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(onClick = onOpenLibrary)
                                    .padding(6.dp),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            if (recent.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            title = "Nothing scanned yet",
                            message = "Your documents stay on this phone. Nothing is uploaded, " +
                                "and everything here works in Airplane Mode.",
                            action = {
                                KobePrimaryButton(
                                    text = "Scan your first document",
                                    icon = Icons.Rounded.DocumentScanner,
                                    onClick = { startScan(ScanMode.BATCH) },
                                )
                            },
                        )
                    }
                }
            } else {
                // A horizontal rail rather than a grid: recents are about the last few things you
                // touched, and a rail says that without pretending to be the whole library.
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(recent, key = { it.id }) { document ->
                            DocumentTile(
                                document = document,
                                onClick = { onOpenDocument(document.id) },
                                modifier = Modifier.width(158.dp),
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    ) {
                        com.kobe.camscanner.core.ui.components.KobeOutlineButton(
                            text = "Open library",
                            onClick = onOpenLibrary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            item { Spacer(Modifier.navigationBarsPadding()) }
        }

        KobeProgressVeil(
            visible = importing,
            label = "Importing",
            detail = "Running your pictures through the scanner",
        )
    }
}

/**
 * Looks like the search field but is a button. Home never owns a keyboard — tapping here goes
 * straight to the search screen, which focuses its own field on arrival.
 */
@Composable
private fun SearchEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val extra = KobeTheme.extra
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(KobeRadius.chip)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                androidx.compose.foundation.BorderStroke(1.dp, extra.hairline),
                KobeRadius.chip,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = stringResource(R.string.cd_search),
            tint = extra.inkFaint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Search documents and text",
            style = MaterialTheme.typography.bodyMedium,
            color = extra.inkFaint,
        )
    }
}

/** A compact square action. Icon over label, so four fit across the narrowest phone. */
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = KobeTheme.extra
    Column(
        modifier = modifier
            .clip(KobeRadius.tile)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = extra.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val MAX_IMPORT = 30
