package com.kobe.camscanner.navigation

/**
 * Every destination in the app, in one place.
 *
 * Routes carry ids, never objects. The in-progress scan lives in
 * [com.kobe.camscanner.data.repository.ScanSession] rather than in navigation arguments, so
 * rotating the phone mid-scan cannot lose a page.
 */
object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val CAMERA = "camera"
    const val REVIEW = "review"
    const val TRASH = "trash"

    private const val CROP_BASE = "crop"
    private const val FILTER_BASE = "filter"
    private const val DOCUMENT_BASE = "document"
    private const val FOLDER_BASE = "folder"
    private const val OCR_BASE = "ocr"
    private const val ANNOTATE_BASE = "annotate"
    private const val WATERMARK_BASE = "watermark"

    const val ARG_PAGE_ID = "pageId"
    const val ARG_DOCUMENT_ID = "documentId"
    const val ARG_FOLDER_ID = "folderId"

    const val CROP = "$CROP_BASE/{$ARG_PAGE_ID}"
    const val FILTER = "$FILTER_BASE/{$ARG_PAGE_ID}"
    const val DOCUMENT = "$DOCUMENT_BASE/{$ARG_DOCUMENT_ID}"
    const val FOLDER = "$FOLDER_BASE/{$ARG_FOLDER_ID}"
    const val OCR = "$OCR_BASE/{$ARG_DOCUMENT_ID}"
    const val ANNOTATE = "$ANNOTATE_BASE/{$ARG_PAGE_ID}"
    const val WATERMARK = "$WATERMARK_BASE/{$ARG_DOCUMENT_ID}"

    fun crop(pageId: String) = "$CROP_BASE/$pageId"
    fun filter(pageId: String) = "$FILTER_BASE/$pageId"
    fun document(documentId: Long) = "$DOCUMENT_BASE/$documentId"
    fun folder(folderId: Long) = "$FOLDER_BASE/$folderId"
    fun ocr(documentId: Long) = "$OCR_BASE/$documentId"
    fun annotate(pageId: String) = "$ANNOTATE_BASE/$pageId"
    fun watermark(documentId: Long) = "$WATERMARK_BASE/$documentId"
}
