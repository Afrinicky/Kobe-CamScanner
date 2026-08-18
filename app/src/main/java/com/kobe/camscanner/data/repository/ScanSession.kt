package com.kobe.camscanner.data.repository

import com.kobe.camscanner.core.storage.KobeStorage
import com.kobe.camscanner.domain.model.Adjustments
import com.kobe.camscanner.domain.model.DocumentType
import com.kobe.camscanner.domain.model.Quad
import com.kobe.camscanner.domain.model.ScanFilter
import com.kobe.camscanner.domain.model.ScanMode
import com.kobe.camscanner.scanner.ScanProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One page inside an in-progress scan, before it becomes a saved document. */
data class SessionPage(
    val pageId: String,
    val originalFile: File,
    val processedFile: File,
    val thumbnailFile: File,
    val quad: Quad?,
    val filter: ScanFilter,
    val adjustments: Adjustments,
    val rotationDegrees: Int,
    val widthPx: Int,
    val heightPx: Int,
    val ocrText: String? = null,
    /** Bumped on every re-render so image loaders refetch instead of serving a stale cache entry. */
    val revision: Int = 0,
)

data class ScanSessionState(
    val sessionId: String,
    val pages: List<SessionPage> = emptyList(),
    val mode: ScanMode = ScanMode.BATCH,
    val suggestedTitle: String? = null,
    val documentType: DocumentType = DocumentType.DOCUMENT,
    /** Set when the session is editing an already-saved document rather than creating one. */
    val editingDocumentId: Long? = null,
) {
    val pageCount: Int get() = pages.size
    val isEmpty: Boolean get() = pages.isEmpty()
}

/**
 * The scan currently being built.
 *
 * This is process-scoped state deliberately: the camera, the crop screen, the filter screen and the
 * page editor are four destinations that all operate on one growing list of pages, and passing that
 * list through navigation arguments would mean serialising bitmaps' worth of metadata on every hop.
 * A session is cheap to abandon — [discard] deletes its whole directory.
 */
@Singleton
class ScanSession @Inject constructor(
    private val storage: KobeStorage,
) {
    private val _state = MutableStateFlow(ScanSessionState(sessionId = newSessionId()))
    val state: StateFlow<ScanSessionState> = _state.asStateFlow()

    val current: ScanSessionState get() = _state.value

    /** Starts a fresh session, discarding anything left from an abandoned one. */
    fun start(mode: ScanMode = ScanMode.BATCH, editingDocumentId: Long? = null) {
        val previous = _state.value
        if (previous.pages.isNotEmpty() && previous.editingDocumentId == null) {
            storage.deleteSession(previous.sessionId)
        }
        _state.value = ScanSessionState(
            sessionId = newSessionId(),
            mode = mode,
            editingDocumentId = editingDocumentId,
        )
    }

    /** Resumes into an existing session id — used when re-editing a saved document's pages. */
    fun resume(state: ScanSessionState) {
        _state.value = state
    }

    fun setMode(mode: ScanMode) = _state.update { it.copy(mode = mode) }

    fun setSuggestedTitle(title: String?, type: DocumentType = DocumentType.DOCUMENT) =
        _state.update { it.copy(suggestedTitle = title, documentType = type) }

    fun addPage(processed: ScanProcessor.ProcessedPage): SessionPage {
        val page = SessionPage(
            pageId = processed.pageId,
            originalFile = processed.originalFile,
            processedFile = processed.processedFile,
            thumbnailFile = processed.thumbnailFile,
            quad = processed.quad,
            filter = processed.filter,
            adjustments = processed.adjustments,
            rotationDegrees = processed.rotationDegrees,
            widthPx = processed.widthPx,
            heightPx = processed.heightPx,
        )
        _state.update { it.copy(pages = it.pages + page) }
        return page
    }

    fun replacePage(pageId: String, processed: ScanProcessor.ProcessedPage) {
        _state.update { state ->
            state.copy(
                pages = state.pages.map { page ->
                    if (page.pageId != pageId) {
                        page
                    } else {
                        page.copy(
                            processedFile = processed.processedFile,
                            thumbnailFile = processed.thumbnailFile,
                            quad = processed.quad,
                            filter = processed.filter,
                            adjustments = processed.adjustments,
                            rotationDegrees = processed.rotationDegrees,
                            widthPx = processed.widthPx,
                            heightPx = processed.heightPx,
                            revision = page.revision + 1,
                        )
                    }
                },
            )
        }
    }

    fun setPageOcr(pageId: String, text: String?) {
        _state.update { state ->
            state.copy(
                pages = state.pages.map { if (it.pageId == pageId) it.copy(ocrText = text) else it },
            )
        }
    }

    /**
     * Marks a page's image as changed without re-running the pipeline. Editors that write the page
     * file directly — annotation, watermark — call this so image caches refetch.
     */
    fun bumpRevision(pageId: String) {
        _state.update { state ->
            state.copy(
                pages = state.pages.map {
                    if (it.pageId == pageId) it.copy(revision = it.revision + 1) else it
                },
            )
        }
    }

    fun removePage(pageId: String) {
        _state.update { state ->
            val page = state.pages.firstOrNull { it.pageId == pageId }
            page?.let {
                it.originalFile.delete()
                it.processedFile.delete()
                it.thumbnailFile.delete()
            }
            state.copy(pages = state.pages.filterNot { it.pageId == pageId })
        }
    }

    fun duplicatePage(pageId: String) {
        _state.update { state ->
            val index = state.pages.indexOfFirst { it.pageId == pageId }
            if (index < 0) return@update state
            val source = state.pages[index]
            val copyId = UUID.randomUUID().toString().take(8)
            val processedCopy = storage.processedFile(state.sessionId, copyId)
            val thumbCopy = storage.thumbnailFile("${state.sessionId}-$copyId")
            runCatching {
                source.processedFile.copyTo(processedCopy, overwrite = true)
                source.thumbnailFile.copyTo(thumbCopy, overwrite = true)
            }.onFailure { return@update state }

            val copy = source.copy(
                pageId = copyId,
                processedFile = processedCopy,
                thumbnailFile = thumbCopy,
                revision = 0,
            )
            state.copy(pages = state.pages.toMutableList().apply { add(index + 1, copy) })
        }
    }

    /** Drag-to-reorder in the page editor (SDS 15). */
    fun movePage(from: Int, to: Int) {
        _state.update { state ->
            if (from !in state.pages.indices || to !in state.pages.indices) return@update state
            val pages = state.pages.toMutableList()
            pages.add(to, pages.removeAt(from))
            state.copy(pages = pages)
        }
    }

    fun rotatePage(pageId: String, delta: Int) {
        _state.update { state ->
            state.copy(
                pages = state.pages.map {
                    if (it.pageId != pageId) it
                    else it.copy(rotationDegrees = ((it.rotationDegrees + delta) % 360 + 360) % 360)
                },
            )
        }
    }

    fun page(pageId: String): SessionPage? = _state.value.pages.firstOrNull { it.pageId == pageId }

    /** Called after the session has been saved as a document; leaves the files in place. */
    fun clear() {
        _state.value = ScanSessionState(sessionId = newSessionId())
    }

    /** Abandons the session and removes every file it created. */
    fun discard() {
        val id = _state.value.sessionId
        _state.value = ScanSessionState(sessionId = newSessionId())
        storage.deleteSession(id)
    }

    private fun newSessionId(): String = "s-" + UUID.randomUUID().toString().take(12)
}
