package com.kobe.camscanner.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.DocumentType
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.domain.model.ScanFilter
import com.kobe.camscanner.domain.model.ScanPage

/**
 * The local index.
 *
 * A note on SDS 23/24, which ask for local metadata files and forbid a server database: this is a
 * SQLite file inside the app's own sandbox, created and read only by Kobe. It exists because
 * full-text search across the OCR of hundreds of documents (SDS 25) has to be instant, and scanning
 * hundreds of JSON sidecars on every keystroke is not. Nothing here is shared, synced or uploaded —
 * the PDFs and page images on disk remain the authoritative artefacts, and this table can be
 * rebuilt from them.
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("folder_id"),
        Index("updated_at"),
        Index("trashed_at"),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    @ColumnInfo(name = "page_count") val pageCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "doc_type") val type: String = DocumentType.DOCUMENT.name,
    @ColumnInfo(name = "has_ocr") val hasOcr: Boolean = false,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    @ColumnInfo(name = "pdf_path") val pdfPath: String? = null,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
    /** Scan session directory under Scans/, so a document's page files can be found and purged. */
    @ColumnInfo(name = "session_id") val sessionId: String,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("document_id")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "document_id") val documentId: Long,
    @ColumnInfo(name = "page_index") val index: Int,
    @ColumnInfo(name = "original_path") val originalPath: String,
    @ColumnInfo(name = "processed_path") val processedPath: String,
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    val filter: String = ScanFilter.AUTO.name,
    /** Five adjustment values, comma separated. Cheaper and clearer than five columns. */
    val adjustments: String = "0,0,0,0,0",
    @ColumnInfo(name = "rotation_degrees") val rotationDegrees: Int = 0,
    /** Eight normalised floats, comma separated: TL, TR, BR, BL. Null means no crop. */
    val corners: String? = null,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    @ColumnInfo(name = "width_px") val widthPx: Int = 0,
    @ColumnInfo(name = "height_px") val heightPx: Int = 0,
)

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "color_index") val colorIndex: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Full-text index over each document's title and recognised text, so SDS 25's search returns as
 * the user types. FTS4 rather than FTS5 because FTS4 is present in every Android SQLite build back
 * to API 21; FTS5 is not guaranteed before API 30.
 */
@Fts4
@Entity(tableName = "document_search")
data class DocumentSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val title: String,
    @ColumnInfo(name = "ocr_text") val ocrText: String,
)

// ------------------------------------------------------------------ mapping

fun DocumentEntity.toDomain(): ScanDocument = ScanDocument(
    id = id,
    title = title,
    folderId = folderId,
    pageCount = pageCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    type = runCatching { DocumentType.valueOf(type) }.getOrDefault(DocumentType.DOCUMENT),
    hasOcr = hasOcr,
    thumbnailPath = thumbnailPath,
    pdfPath = pdfPath,
    sizeBytes = sizeBytes,
    isFavourite = isFavourite,
    trashedAt = trashedAt,
)

fun ScanDocument.toEntity(sessionId: String): DocumentEntity = DocumentEntity(
    id = id,
    title = title,
    folderId = folderId,
    pageCount = pageCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
    type = type.name,
    hasOcr = hasOcr,
    thumbnailPath = thumbnailPath,
    pdfPath = pdfPath,
    sizeBytes = sizeBytes,
    isFavourite = isFavourite,
    trashedAt = trashedAt,
    sessionId = sessionId,
)

fun PageEntity.toDomain(): ScanPage = ScanPage(
    id = id,
    documentId = documentId,
    index = index,
    originalPath = originalPath,
    processedPath = processedPath,
    thumbnailPath = thumbnailPath,
    filter = runCatching { ScanFilter.valueOf(filter) }.getOrDefault(ScanFilter.AUTO),
    adjustments = Serialisation.adjustmentsFrom(adjustments),
    rotationDegrees = rotationDegrees,
    corners = Serialisation.quadFrom(corners),
    ocrText = ocrText,
    widthPx = widthPx,
    heightPx = heightPx,
)

fun ScanPage.toEntity(): PageEntity = PageEntity(
    id = id,
    documentId = documentId,
    index = index,
    originalPath = originalPath,
    processedPath = processedPath,
    thumbnailPath = thumbnailPath,
    filter = filter.name,
    adjustments = Serialisation.adjustmentsTo(adjustments),
    rotationDegrees = rotationDegrees,
    corners = Serialisation.quadTo(corners),
    ocrText = ocrText,
    widthPx = widthPx,
    heightPx = heightPx,
)

fun FolderEntity.toDomain(documentCount: Int = 0): Folder = Folder(
    id = id,
    name = name,
    colorIndex = colorIndex,
    createdAt = createdAt,
    documentCount = documentCount,
)

/**
 * Compact text encodings for the two composite values. Written by hand rather than pulled in with
 * a JSON library: two shapes, both fixed-arity, and the app has no other need for a serialiser.
 */
object Serialisation {

    fun adjustmentsTo(value: Adjustments): String = listOf(
        value.brightness, value.contrast, value.sharpness, value.saturation, value.exposure,
    ).joinToString(",")

    fun adjustmentsFrom(raw: String?): Adjustments {
        val parts = raw?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: return Adjustments.NEUTRAL
        if (parts.size != 5) return Adjustments.NEUTRAL
        return Adjustments(parts[0], parts[1], parts[2], parts[3], parts[4])
    }

    fun quadTo(quad: Quad?): String? = quad?.points?.joinToString(",") { "${it.x},${it.y}" }

    fun quadFrom(raw: String?): Quad? {
        val parts = raw?.split(",")?.mapNotNull { it.trim().toFloatOrNull() } ?: return null
        if (parts.size != 8) return null
        return Quad(
            com.kobe.camscanner.domain.model.PointN(parts[0], parts[1]),
            com.kobe.camscanner.domain.model.PointN(parts[2], parts[3]),
            com.kobe.camscanner.domain.model.PointN(parts[4], parts[5]),
            com.kobe.camscanner.domain.model.PointN(parts[6], parts[7]),
        )
    }
}
