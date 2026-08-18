package com.kobe.camscanner.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kobe.camscanner.core.common.Formatting
import com.kobe.camscanner.core.ui.components.KobeBadge
import com.kobe.camscanner.core.ui.components.KobeCard
import com.kobe.camscanner.core.ui.theme.KobeRadius
import com.kobe.camscanner.core.ui.theme.KobeTheme
import com.kobe.camscanner.domain.model.DocumentType
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.ScanDocument

/**
 * A document as a page.
 *
 * The thumbnail is shown at a paper aspect ratio with a soft gradient at its foot, so the title
 * always sits on a readable ground whatever the scan looks like. That single detail is what keeps a
 * grid of white pages from turning into an unreadable wall.
 */
@Composable
fun DocumentTile(
    document: ScanDocument,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val extra = KobeTheme.extra
    KobeCard(
        modifier = modifier.semantics {
            contentDescription = "${document.title}, ${Formatting.pageCount(document.pageCount)}"
        },
        onClick = onClick,
        onLongClick = onLongClick,
        container = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(KobeRadius.card)
                .background(extra.surfaceMuted),
        ) {
            AsyncImage(
                model = document.thumbnailFile,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.35f),
                        ),
                    ),
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (document.isFavourite) {
                    TileGlyph(Icons.Rounded.Star, "Favourite")
                }
                if (document.hasOcr) {
                    TileGlyph(Icons.Rounded.TextFields, "Contains recognised text")
                }
            }
            Text(
                text = Formatting.pageCount(document.pageCount),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = Formatting.relativeDate(document.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = extra.inkFaint,
                maxLines = 1,
            )
        }
    }
}

/** The list variant, used by search results and the trash. */
@Composable
fun DocumentRow(
    document: ScanDocument,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val extra = KobeTheme.extra
    KobeCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp, 66.dp)
                    .clip(KobeRadius.page)
                    .background(extra.surfaceMuted),
            ) {
                AsyncImage(
                    model = document.thumbnailFile,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle ?: buildString {
                        append(Formatting.pageCount(document.pageCount))
                        append(" · ")
                        append(Formatting.relativeDate(document.updatedAt))
                        if (document.sizeBytes > 0) {
                            append(" · ")
                            append(Formatting.fileSize(document.sizeBytes))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = extra.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (document.type != DocumentType.DOCUMENT) {
                    Spacer(Modifier.height(6.dp))
                    KobeBadge(
                        text = document.type.label,
                        accent = accentFor(document.type),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

/** A folder as a tab-like card, coloured from a small fixed palette. */
@Composable
fun FolderChipCard(
    folder: Folder,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extra = KobeTheme.extra
    val accent = folderAccents()[folder.colorIndex % folderAccents().size]
    KobeCard(
        modifier = modifier.width(150.dp),
        onClick = onClick,
        elevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(KobeRadius.page)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp, 11.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (folder.documentCount == 1) "1 document" else "${folder.documentCount} documents",
                style = MaterialTheme.typography.bodySmall,
                color = extra.inkFaint,
            )
        }
    }
}

@Composable
private fun TileGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Document types get stable colours so the library stays scannable at a glance. */
@Composable
fun accentFor(type: DocumentType): Color {
    val extra = KobeTheme.extra
    return when (type) {
        DocumentType.RECEIPT -> extra.amber
        DocumentType.INVOICE -> extra.indigo
        DocumentType.ID_CARD, DocumentType.PASSPORT -> extra.teal
        DocumentType.BUSINESS_CARD -> extra.violet
        DocumentType.CERTIFICATE -> extra.emerald
        DocumentType.WHITEBOARD, DocumentType.BOOK -> extra.slate
        DocumentType.PHOTO -> extra.slate
        DocumentType.DOCUMENT -> MaterialTheme.colorScheme.primary
    }
}

@Composable
private fun folderAccents(): List<Color> {
    val extra = KobeTheme.extra
    return listOf(
        MaterialTheme.colorScheme.primary,
        extra.indigo,
        extra.emerald,
        extra.amber,
        extra.violet,
        extra.teal,
    )
}
