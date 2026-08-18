package com.kobe.camscanner.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query(
        """
        SELECT * FROM documents
        WHERE trashed_at IS NULL
        ORDER BY updated_at DESC
        """,
    )
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT * FROM documents
        WHERE trashed_at IS NULL
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT * FROM documents
        WHERE trashed_at IS NULL AND folder_id IS :folderId
        ORDER BY updated_at DESC
        """,
    )
    fun observeInFolder(folderId: Long?): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashed_at IS NOT NULL ORDER BY trashed_at DESC")
    fun observeTrash(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashed_at IS NULL AND is_favourite = 1 ORDER BY updated_at DESC")
    fun observeFavourites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: Long): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT title FROM documents WHERE trashed_at IS NULL")
    suspend fun allTitles(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("UPDATE documents SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE documents SET folder_id = :folderId, updated_at = :now WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, now: Long)

    @Query("UPDATE documents SET is_favourite = :favourite WHERE id = :id")
    suspend fun setFavourite(id: Long, favourite: Boolean)

    @Query("UPDATE documents SET trashed_at = :now WHERE id IN (:ids)")
    suspend fun moveToTrash(ids: List<Long>, now: Long)

    @Query("UPDATE documents SET trashed_at = NULL, updated_at = :now WHERE id IN (:ids)")
    suspend fun restoreFromTrash(ids: List<Long>, now: Long)

    @Query("SELECT * FROM documents WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff")
    suspend fun expiredTrash(cutoff: Long): List<DocumentEntity>

    @Query("SELECT COUNT(*) FROM documents WHERE trashed_at IS NULL")
    fun observeCount(): Flow<Int>
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE document_id = :documentId ORDER BY page_index ASC")
    fun observeForDocument(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE document_id = :documentId ORDER BY page_index ASC")
    suspend fun getForDocument(documentId: Long): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(pages: List<PageEntity>): List<Long>

    @Update
    suspend fun update(page: PageEntity)

    @Query("DELETE FROM pages WHERE document_id = :documentId")
    suspend fun deleteForDocument(documentId: Long)

    @Query("SELECT ocr_text FROM pages WHERE document_id = :documentId ORDER BY page_index ASC")
    suspend fun ocrTextFor(documentId: Long): List<String?>

    @Query("UPDATE pages SET ocr_text = :text WHERE id = :pageId")
    suspend fun setOcrText(pageId: Long, text: String?)
}

@Dao
interface FolderDao {

    @Query(
        """
        SELECT f.*, (
            SELECT COUNT(*) FROM documents d
            WHERE d.folder_id = f.id AND d.trashed_at IS NULL
        ) AS documentCount
        FROM folders f
        ORDER BY f.name COLLATE NOCASE ASC
        """,
    )
    fun observeAllWithCounts(): Flow<List<FolderWithCount>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getById(id: Long): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM folders")
    suspend fun count(): Int
}

data class FolderWithCount(
    @Embedded val folder: FolderEntity,
    val documentCount: Int,
)

@Dao
interface SearchDao {

    /**
     * Matches title and OCR text in one pass and returns whole documents, newest first.
     *
     * The caller passes an FTS query built by [com.kobe.camscanner.data.repository.FtsQuery], not
     * raw user input — an unescaped apostrophe or a bare `*` is a syntax error in FTS4.
     */
    @Transaction
    @Query(
        """
        SELECT d.* FROM documents d
        JOIN document_search s ON s.rowid = d.id
        WHERE document_search MATCH :query AND d.trashed_at IS NULL
        ORDER BY d.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 100): List<DocumentEntity>

    /** Fallback used for one- and two-character queries, where FTS prefix matching is wasteful. */
    @Query(
        """
        SELECT * FROM documents
        WHERE trashed_at IS NULL AND title LIKE '%' || :term || '%'
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTitles(term: String, limit: Int = 100): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DocumentSearchEntity)

    @Query("DELETE FROM document_search WHERE rowid = :documentId")
    suspend fun remove(documentId: Long)

    /** Returns the snippet of OCR text around the first hit, for the search result subtitle. */
    @Query(
        """
        SELECT snippet(document_search, '[', ']', '...', -1, 12)
        FROM document_search
        WHERE rowid = :documentId AND document_search MATCH :query
        """,
    )
    suspend fun snippet(documentId: Long, query: String): String?
}
