package com.kobe.camscanner.data.repository

import com.kobe.camscanner.core.common.FileNames
import com.kobe.camscanner.core.common.IoDispatcher
import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.data.local.DocumentDao
import com.kobe.camscanner.data.local.DocumentEntity
import com.kobe.camscanner.data.local.DocumentSearchEntity
import com.kobe.camscanner.data.local.FolderDao
import com.kobe.camscanner.data.local.FolderEntity
import com.kobe.camscanner.data.local.KobeDatabase
import com.kobe.camscanner.data.local.PageDao
import com.kobe.camscanner.data.local.SearchDao
import com.kobe.camscanner.data.local.toDomain
import com.kobe.camscanner.data.local.toEntity
import com.kobe.camscanner.domain.model.DocumentType
import com.kobe.camscanner.domain.model.Folder
import com.kobe.camscanner.domain.model.ScanDocument
import com.kobe.camscanner.domain.model.ScanPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single door to the local library.
 *
 * Two invariants are enforced here rather than left to callers: the full-text index is written in
 * the same operation as the document it describes, and deleting a document deletes its files. Every
 * screen therefore sees a library where search results always open and thumbnails always load.
 */
@Singleton
class DocumentRepository @Inject constructor(
    private val documentDao: DocumentDao,
    private val pageDao: PageDao,
    private val folderDao: FolderDao,
    private val searchDao: SearchDao,
    private val storage: KobeStorage,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    fun observeRecent(limit: Int = 12): Flow<List<ScanDocument>> =
        documentDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeAll(): Flow<List<ScanDocument>> =
        documentDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeInFolder(folderId: Long?): Flow<List<ScanDocument>> =
        documentDao.observeInFolder(folderId).map { list -> list.map { it.toDomain() } }

    fun observeFavourites(): Flow<List<ScanDocument>> =
        documentDao.observeFavourites().map { list -> list.map { it.toDomain() } }

    fun observeTrash(): Flow<List<ScanDocument>> =
        documentDao.observeTrash().map { list -> list.map { it.toDomain() } }

    fun observeDocument(id: Long): Flow<ScanDocument?> =
        documentDao.observeById(id).map { it?.toDomain() }

    fun observePages(documentId: Long): Flow<List<ScanPage>> =
        pageDao.observeForDocument(documentId).map { list -> list.map { it.toDomain() } }

    fun observeFolders(): Flow<List<Folder>> =
        folderDao.observeAllWithCounts().map { list ->
            list.map { it.folder.toDomain(it.documentCount) }
        }

    fun observeDocumentCount(): Flow<Int> = documentDao.observeCount()

    suspend fun getDocument(id: Long): ScanDocument? = withContext(dispatcher) {
        documentDao.getById(id)?.toDomain()
    }

    suspend fun getPages(documentId: Long): List<ScanPage> = withContext(dispatcher) {
        pageDao.getForDocument(documentId).map { it.toDomain() }
    }

    /**
     * Saves a new document and its pages, then indexes it for search.
     * Returns the new document id.
     */
    suspend fun createDocument(
        title: String,
        sessionId: String,
        pages: List<ScanPage>,
        folderId: Long? = null,
        type: DocumentType = DocumentType.DOCUMENT,
        pdfFile: File? = null,
        thumbnailPath: String? = null,
    ): Long = withContext(dispatcher) {
        val now = System.currentTimeMillis()
        val uniqueTitle = FileNames.uniquify(title, documentDao.allTitles().toSet())

        val entity = DocumentEntity(
            title = uniqueTitle,
            folderId = folderId,
            pageCount = pages.size,
            createdAt = now,
            updatedAt = now,
            type = type.name,
            hasOcr = pages.any { !it.ocrText.isNullOrBlank() },
            thumbnailPath = thumbnailPath ?: pages.firstOrNull()?.thumbnailPath,
            pdfPath = pdfFile?.absolutePath,
            sizeBytes = pdfFile?.length() ?: pages.sumOf { it.processedFile.length() },
            sessionId = sessionId,
        )
        val id = documentDao.insert(entity)
        pageDao.insertAll(pages.mapIndexed { index, page -> page.copy(index = index, documentId = id).toEntity() })
        reindex(id, uniqueTitle, pages.mapNotNull { it.ocrText })
        id
    }

    /** Replaces the page set of an existing document (after reorder, delete, retake, or add). */
    suspend fun replacePages(documentId: Long, pages: List<ScanPage>) = withContext(dispatcher) {
        pageDao.deleteForDocument(documentId)
        pageDao.insertAll(
            pages.mapIndexed { index, page -> page.copy(index = index, documentId = documentId).toEntity() },
        )
        val current = documentDao.getById(documentId) ?: return@withContext
        documentDao.update(
            current.copy(
                pageCount = pages.size,
                updatedAt = System.currentTimeMillis(),
                hasOcr = pages.any { !it.ocrText.isNullOrBlank() },
                thumbnailPath = pages.firstOrNull()?.thumbnailPath ?: current.thumbnailPath,
            ),
        )
        reindex(documentId, current.title, pages.mapNotNull { it.ocrText })
    }

    suspend fun attachPdf(documentId: Long, pdfFile: File) = withContext(dispatcher) {
        val current = documentDao.getById(documentId) ?: return@withContext
        documentDao.update(
            current.copy(
                pdfPath = pdfFile.absolutePath,
                sizeBytes = pdfFile.length(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun rename(documentId: Long, title: String) = withContext(dispatcher) {
        val taken = documentDao.allTitles().toSet() - (documentDao.getById(documentId)?.title ?: "")
        val unique = FileNames.uniquify(title, taken)
        documentDao.rename(documentId, unique, System.currentTimeMillis())
        val ocr = pageDao.ocrTextFor(documentId).filterNotNull()
        reindex(documentId, unique, ocr)
    }

    suspend fun moveToFolder(documentId: Long, folderId: Long?) = withContext(dispatcher) {
        documentDao.moveToFolder(documentId, folderId, System.currentTimeMillis())
    }

    suspend fun setFavourite(documentId: Long, favourite: Boolean) = withContext(dispatcher) {
        documentDao.setFavourite(documentId, favourite)
    }

    suspend fun setPageOcr(pageId: Long, text: String?) = withContext(dispatcher) {
        pageDao.setOcrText(pageId, text)
    }

    /** Soft delete (SDS 23: Trash/). Files stay on disk until the trash is emptied. */
    suspend fun moveToTrash(ids: List<Long>) = withContext(dispatcher) {
        documentDao.moveToTrash(ids, System.currentTimeMillis())
        ids.forEach { searchDao.remove(it) }
    }

    suspend fun restoreFromTrash(ids: List<Long>) = withContext(dispatcher) {
        documentDao.restoreFromTrash(ids, System.currentTimeMillis())
        ids.forEach { id ->
            val doc = documentDao.getById(id) ?: return@forEach
            reindex(id, doc.title, pageDao.ocrTextFor(id).filterNotNull())
        }
    }

    /** Permanent delete: row, pages, PDF, page images, thumbnails. */
    suspend fun deleteForever(ids: List<Long>) = withContext(dispatcher) {
        ids.forEach { id ->
            val entity = documentDao.getById(id) ?: return@forEach
            entity.pdfPath?.let { File(it).delete() }
            entity.thumbnailPath?.let { File(it).delete() }
            pageDao.getForDocument(id).forEach { page ->
                File(page.originalPath).delete()
                File(page.processedPath).delete()
                page.thumbnailPath?.let { File(it).delete() }
            }
            storage.deleteSession(entity.sessionId)
            searchDao.remove(id)
            documentDao.delete(entity)
        }
    }

    /** Purges trash older than [retentionDays]; called on app start. */
    suspend fun purgeExpiredTrash(retentionDays: Int = TRASH_RETENTION_DAYS) = withContext(dispatcher) {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        val expired = documentDao.expiredTrash(cutoff)
        if (expired.isNotEmpty()) deleteForever(expired.map { it.id })
    }

    // ------------------------------------------------------------------ folders

    suspend fun createFolder(name: String): Long = withContext(dispatcher) {
        folderDao.insert(
            FolderEntity(name = FileNames.sanitise(name, "Folder"), createdAt = System.currentTimeMillis()),
        )
    }

    suspend fun renameFolder(id: Long, name: String) = withContext(dispatcher) {
        folderDao.rename(id, FileNames.sanitise(name, "Folder"))
    }

    /** Deleting a folder never deletes its documents; they fall back to the unfiled library. */
    suspend fun deleteFolder(id: Long) = withContext(dispatcher) {
        folderDao.delete(id)
    }

    suspend fun seedDefaultFoldersIfEmpty() = withContext(dispatcher) {
        if (folderDao.count() > 0) return@withContext
        val now = System.currentTimeMillis()
        KobeDatabase.DEFAULT_FOLDERS.forEach { name ->
            folderDao.insert(FolderEntity(name = name, createdAt = now))
        }
    }

    // ------------------------------------------------------------------ search

    /** Local search across filename and OCR text (SDS 25). */
    suspend fun search(rawQuery: String, limit: Int = 100): List<SearchHit> = withContext(dispatcher) {
        val term = rawQuery.trim()
        if (term.isEmpty()) return@withContext emptyList()

        if (term.length < 3) {
            return@withContext searchDao.searchTitles(term, limit).map {
                SearchHit(it.toDomain(), snippet = null)
            }
        }

        val ftsQuery = FtsQuery.build(term) ?: return@withContext emptyList()
        val results = runCatching { searchDao.search(ftsQuery, limit) }.getOrElse {
            // A malformed FTS expression must never surface as a crash; degrade to title matching.
            return@withContext searchDao.searchTitles(term, limit).map {
                SearchHit(it.toDomain(), snippet = null)
            }
        }
        results.map { entity ->
            val snippet = runCatching { searchDao.snippet(entity.id, ftsQuery) }.getOrNull()
            SearchHit(entity.toDomain(), snippet?.takeIf { it.isNotBlank() })
        }
    }

    private suspend fun reindex(documentId: Long, title: String, ocrText: List<String>) {
        searchDao.upsert(
            DocumentSearchEntity(
                rowId = documentId,
                title = title,
                ocrText = ocrText.joinToString("\n").take(MAX_INDEXED_CHARS),
            ),
        )
    }

    data class SearchHit(val document: ScanDocument, val snippet: String?)

    companion object {
        const val TRASH_RETENTION_DAYS = 30

        /**
         * Cap on indexed text per document. A 200-page scan can produce megabytes of OCR; past
         * this point the index costs more than the extra recall is worth on a phone.
         */
        const val MAX_INDEXED_CHARS = 200_000
    }
}

/**
 * Builds a safe FTS4 MATCH expression from free-form user input.
 *
 * FTS4's grammar is small but unforgiving: an unbalanced quote, a leading `*`, or a bare `-` is a
 * syntax error rather than a no-op. Everything is therefore reduced to quoted tokens, with a prefix
 * wildcard on the last one so results narrow as the user types.
 */
object FtsQuery {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    fun build(raw: String): String? {
        val tokens = TOKEN.findAll(raw.lowercase()).map { it.value }.filter { it.isNotBlank() }.toList()
        if (tokens.isEmpty()) return null
        return tokens.mapIndexed { index, token ->
            if (index == tokens.lastIndex) "\"$token\"*" else "\"$token\""
        }.joinToString(" ")
    }
}
