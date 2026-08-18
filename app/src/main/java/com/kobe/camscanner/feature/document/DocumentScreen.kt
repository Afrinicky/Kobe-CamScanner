package com.kobe.camscanner.feature.document

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.kobe.camscanner.R
import com.kobe.camscanner.core.common.FailureReason
import com.kobe.camscanner.core.common.Formatting
import com.kobe.camscanner.core.ui.components.KobeIconButton
import com.kobe.camscanner.core.ui.components.KobeProgressVeil
import com.kobe.camscanner.core.ui.components.KobeTopBar
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.share.ShareHelper

/**
 * A saved document (SDS 22, 27, 28, 29).
 *
 * The page pager is the whole screen; the actions sit in one bar at the bottom with Share first,
 * because sharing is what most scans exist for. Everything rarer lives behind the overflow so the
 * bar stays four items wide on the narrowest phone.
 */
@Composable
fun DocumentScreen(
    onBack: () -> Unit,
    onEditPages: () -> Unit,
    onAddWatermark: (Long) -> Unit,
    viewModel: DocumentViewModel = hiltViewModel(),
) {
    val document by viewModel.document.collectAsStateWithLifecycle()
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    var overflowOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(ShareHelper.MIME_PDF),
    ) { uri -> uri?.let(viewModel::exportTo) }

    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    LaunchedEffect(state.message, state.error) {
        val text = state.message ?: state.error?.let { context.getString(errorRes(it)) }
        if (text != null) {
            snackbarHost.showSnackbar(text)
            viewModel.clearMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            KobeTopBar(
                title = document?.title ?: "Document",
                subtitle = document?.let {
                    buildString {
                        append(Formatting.pageCount(it.pageCount))
                        append(" · ")
                        append(Formatting.relativeDate(it.updatedAt))
                        if (it.sizeBytes > 0) {
                            append(" · ")
                            append(Formatting.fileSize(it.sizeBytes))
                        }
                    }
                },
                onBack = onBack,
                actions = {
                    KobeIconButton(
                        icon = if (document?.isFavourite == true) Icons.Rounded.Star
                        else Icons.Rounded.StarBorder,
                        contentDescription = "Star document",
                        onClick = viewModel::toggleFavourite,
                        tint = if (document?.isFavourite == true) KobeTheme.extra.amber
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Box {
                        KobeIconButton(
                            icon = Icons.Rounded.MoreVert,
                            contentDescription = stringResource(R.string.cd_more),
                            onClick = { overflowOpen = true },
                        )
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            OverflowItem(Icons.Rounded.DriveFileRenameOutline, "Rename") {
                                overflowOpen = false
                                viewModel.openRenameDialog()
                            }
                            OverflowItem(Icons.Rounded.TextFields, "Extract text") {
                                overflowOpen = false
                                viewModel.extractText()
                            }
                            OverflowItem(Icons.Rounded.Compress, "Compress PDF") {
                                overflowOpen = false
                                viewModel.compress()
                            }
                            OverflowItem(Icons.Rounded.OpenInNew, "Open in PDF viewer") {
                                overflowOpen = false
                                viewModel.openInPdfViewer()
                            }
                            OverflowItem(Icons.Rounded.Info, "Watermark") {
                                overflowOpen = false
                                document?.let { onAddWatermark(it.id) }
                            }
                            OverflowItem(Icons.Rounded.Delete, "Move to trash") {
                                overflowOpen = false
                                viewModel.moveToTrash()
                            }
                        }
                    }
                },
            )

            if (pages.isEmpty()) {
                Box(modifier = Modifier.weight(1f))
            } else {
                val pagerState = rememberPagerState(pageCount = { pages.size })
                Column(modifier = Modifier.weight(1f)) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        pageSpacing = 16.dp,
                    ) { index ->
                        AsyncImage(
                            model = pages[index].processedFile,
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(KobeRadius.page)
                                .background(Color.White),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "${pagerState.currentPage + 1} of ${pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = KobeTheme.extra.inkMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    )
                }
            }

            DocumentActionBar(
                onShare = viewModel::openShareSheet,
                onPrint = viewModel::print,
                onText = viewModel::extractText,
                onEditPages = { viewModel.openInPageEditor(onEditPages) },
            )
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 90.dp),
        )

        if (state.showShareSheet) {
            ShareSheet(
                whatsAppAvailable = state.whatsAppAvailable,
                onWhatsApp = viewModel::shareToWhatsApp,
                onFiles = { exportLauncher.launch(viewModel.suggestedFileName()) },
                onSharePdf = viewModel::sharePdf,
                onShareImages = viewModel::shareImages,
                onPrint = viewModel::print,
                onDismiss = viewModel::closeShareSheet,
            )
        }

        if (state.showTextSheet) {
            ExtractedTextSheet(
                text = state.extractedText,
                onShare = viewModel::shareText,
                onDismiss = viewModel::closeTextSheet,
            )
        }

        if (state.showRenameDialog) {
            RenameDialog(
                initial = document?.title.orEmpty(),
                folders = folders,
                currentFolderId = document?.folderId,
                onFolderChange = viewModel::moveToFolder,
                onConfirm = viewModel::rename,
                onDismiss = viewModel::closeRenameDialog,
            )
        }

        KobeProgressVeil(visible = state.busyLabel != null, label = state.busyLabel.orEmpty())
    }
}

/** Four flat actions, evenly divided. No labels hidden behind icons — every one is spelled out. */
@Composable
private fun DocumentActionBar(
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onText: () -> Unit,
    onEditPages: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionItem(Icons.Rounded.Share, "Share", onShare, primary = true)
        ActionItem(Icons.Rounded.Print, "Print", onPrint)
        ActionItem(Icons.Rounded.TextFields, "Text", onText)
        ActionItem(Icons.Rounded.DriveFileRenameOutline, "Edit", onEditPages)
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Column(
        modifier = Modifier
            .clip(KobeRadius.tile)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (primary) MaterialTheme.colorScheme.primary else KobeTheme.extra.inkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverflowItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** Maps a failure to the exact wording SDS 49 specifies. */
private fun errorRes(reason: FailureReason): Int = when (reason) {
    FailureReason.CAMERA_UNAVAILABLE -> R.string.error_camera_unavailable
    FailureReason.NO_DOCUMENT_DETECTED -> R.string.error_no_document
    FailureReason.OCR_FAILED -> R.string.error_ocr_failed
    FailureReason.PDF_FAILED -> R.string.error_pdf_failed
    FailureReason.STORAGE_FAILED -> R.string.error_storage_failed
    FailureReason.IMPORT_FAILED -> R.string.error_import_failed
    FailureReason.UNKNOWN -> R.string.error_storage_failed
}
