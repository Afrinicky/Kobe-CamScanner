package com.kobe.camscanner.feature.document

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.components.KobeTextButton
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.domain.model.Folder as KobeFolder

/**
 * The share sheet (SDS 28).
 *
 * WhatsApp and "Save to Files" are given their own large targets at the top because the spec names
 * them as the two that must be effortless. Everything else drops into the standard Android
 * Sharesheet, which is where users expect the long tail to live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    whatsAppAvailable: Boolean,
    onWhatsApp: () -> Unit,
    onFiles: () -> Unit,
    onSharePdf: () -> Unit,
    onShareImages: () -> Unit,
    onPrint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val extra = KobeTheme.extra

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = KobeRadius.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Share",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Nothing is uploaded. Kobe hands the file to the app you pick.",
                style = MaterialTheme.typography.bodySmall,
                color = extra.inkMuted,
            )
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (whatsAppAvailable) {
                    BigShareTarget(
                        icon = Icons.Rounded.Chat,
                        label = "WhatsApp",
                        accent = extra.emerald,
                        onClick = onWhatsApp,
                        modifier = Modifier.weight(1f),
                    )
                }
                BigShareTarget(
                    icon = Icons.Rounded.Folder,
                    label = "Save to Files",
                    accent = extra.indigo,
                    onClick = onFiles,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            SheetAction(Icons.Rounded.PictureAsPdf, "Share as PDF", onSharePdf)
            SheetAction(Icons.Rounded.Image, "Share page images", onShareImages)
            SheetAction(Icons.Rounded.Print, "Print", onPrint)
            SheetAction(Icons.Rounded.Share, "More apps", onSharePdf)
        }
    }
}

@Composable
private fun BigShareTarget(
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(KobeRadius.card)
            .background(accent.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.24f)), KobeRadius.card)
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KobeRadius.tile)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The recognised text (SDS 21): selectable, copyable, shareable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractedTextSheet(
    text: String?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val extra = KobeTheme.extra

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = KobeRadius.sheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Recognised text",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Read on this device. Nothing was sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.inkMuted,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (text == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .clip(KobeRadius.tile)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.kobe.camscanner.core.ui.components.KobeOutlineButton(
                        text = "Copy",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = { clipboard.setText(AnnotatedString(text)) },
                        modifier = Modifier.weight(1f),
                    )
                    KobePrimaryButton(
                        text = "Share text",
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Rename plus folder assignment, since users almost always want both at the same moment. */
@Composable
fun RenameDialog(
    initial: String,
    folders: List<KobeFolder>,
    currentFolderId: Long?,
    onFolderChange: (Long?) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var selectedFolder by remember { mutableStateOf(currentFolderId) }
    val extra = KobeTheme.extra

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = KobeRadius.card,
        title = { Text("Rename document", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(KobeRadius.button)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(BorderStroke(1.dp, extra.hairline), KobeRadius.button)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (folders.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Folder",
                        style = MaterialTheme.typography.labelMedium,
                        color = extra.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 2.dp),
                    ) {
                        item {
                            KobeChip(
                                label = "None",
                                selected = selectedFolder == null,
                                onClick = {
                                    selectedFolder = null
                                    onFolderChange(null)
                                },
                            )
                        }
                        items(folders) { folder ->
                            KobeChip(
                                label = folder.name,
                                selected = selectedFolder == folder.id,
                                onClick = {
                                    selectedFolder = folder.id
                                    onFolderChange(folder.id)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            KobeTextButton(text = "Save", onClick = { onConfirm(value) })
        },
        dismissButton = {
            KobeTextButton(
                text = "Cancel",
                onClick = onDismiss,
                color = KobeTheme.extra.inkMuted,
            )
        },
    )
}
