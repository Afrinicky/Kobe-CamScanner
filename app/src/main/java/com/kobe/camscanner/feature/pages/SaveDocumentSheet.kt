package com.kobe.camscanner.feature.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.kobe.camscanner.core.common.Formatting
import com.kobe.camscanner.core.ui.components.KobeChip
import com.kobe.camscanner.core.ui.components.KobePrimaryButton
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.PdfCompression
import com.kobe.camscanner.domain.model.PdfPageSize

/**
 * The one place a scan becomes a document (SDS 16, 17).
 *
 * Everything here has a sensible default already applied, so the fast path is: glance at the name,
 * press Save. The page size, compression and searchable-PDF choices sit below in that order because
 * that is how often people change them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveDocumentSheet(
    options: SaveOptionsUi,
    folders: List<Folder>,
    pageCount: Int,
    onTitleChange: (String) -> Unit,
    onFolderChange: (Long?) -> Unit,
    onPageSizeChange: (PdfPageSize) -> Unit,
    onCompressionChange: (PdfCompression) -> Unit,
    onSearchableChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
            Text(
                text = "Save document",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = Formatting.pageCount(pageCount) + " · PDF",
                style = MaterialTheme.typography.bodyMedium,
                color = extra.inkMuted,
            )

            Spacer(Modifier.height(20.dp))
            FieldLabel("Name")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(KobeRadius.button)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(BorderStroke(1.dp, extra.hairline), KobeRadius.button)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (options.title.isEmpty()) {
                    Text(
                        "Document name",
                        style = MaterialTheme.typography.bodyLarge,
                        color = extra.inkFaint,
                    )
                }
                BasicTextField(
                    value = options.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                FieldLabel("Folder")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    item {
                        KobeChip(
                            label = "None",
                            selected = options.folderId == null,
                            onClick = { onFolderChange(null) },
                        )
                    }
                    items(folders) { folder ->
                        KobeChip(
                            label = folder.name,
                            selected = options.folderId == folder.id,
                            onClick = { onFolderChange(folder.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("Page size")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(PdfPageSize.entries.toList()) { size ->
                    KobeChip(
                        label = size.label,
                        selected = options.pageSize == size,
                        onClick = { onPageSizeChange(size) },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            FieldLabel("File size")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(PdfCompression.entries.toList()) { compression ->
                    KobeChip(
                        label = compression.label,
                        selected = options.compression == compression,
                        onClick = { onCompressionChange(compression) },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Searchable PDF",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Recognised text is embedded so the file can be searched and copied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = extra.inkMuted,
                    )
                }
                Spacer(Modifier.height(0.dp))
                Switch(checked = options.searchable, onCheckedChange = onSearchableChange)
            }

            Spacer(Modifier.height(24.dp))
            KobePrimaryButton(
                text = "Save",
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = KobeTheme.extra.inkMuted,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
