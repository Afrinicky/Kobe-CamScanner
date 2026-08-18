package com.kobe.camscanner.domain.model

import java.io.File

/** Stable identity for a document. Generated locally; never a server id. */
typealias DocumentId = Long

/** A saved, multi-page document in the local library. */
data class ScanDocument(
    val id: DocumentId = 0L,
    val title: String,
    val folderId: Long? = null,
    val pageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val type: DocumentType = DocumentType.DOCUMENT,
    val hasOcr: Boolean = false,
    val thumbnailPath: String? = null,
    val pdfPath: String? = null,
    val sizeBytes: Long = 0L,
    val isFavourite: Boolean = false,
    val trashedAt: Long? = null,
) {
    val isInTrash: Boolean get() = trashedAt != null
    val thumbnailFile: File? get() = thumbnailPath?.let(::File)
    val pdfFile: File? get() = pdfPath?.let(::File)
}

/** One page of a document. Kobe keeps the original alongside the processed result so any
 *  enhancement decision stays reversible (SDS 15: "Replace", "Enhance" must be re-runnable). */
data class ScanPage(
    val id: Long = 0L,
    val documentId: DocumentId = 0L,
    val index: Int = 0,
    val originalPath: String,
    val processedPath: String,
    val thumbnailPath: String? = null,
    val filter: ScanFilter = ScanFilter.AUTO,
    val adjustments: Adjustments = Adjustments.NEUTRAL,
    val rotationDegrees: Int = 0,
    val corners: Quad? = null,
    val ocrText: String? = null,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
) {
    val originalFile: File get() = File(originalPath)
    val processedFile: File get() = File(processedPath)
}

/** A local folder. Folders are metadata only — files live in a flat store (SDS 23). */
data class Folder(
    val id: Long = 0L,
    val name: String,
    val colorIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val documentCount: Int = 0,
)

/** Recognised document kinds (SDS 33). Classification is local and heuristic in V1. */
enum class DocumentType(val label: String) {
    DOCUMENT("Document"),
    RECEIPT("Receipt"),
    INVOICE("Invoice"),
    ID_CARD("ID card"),
    PASSPORT("Passport"),
    BUSINESS_CARD("Business card"),
    WHITEBOARD("Whiteboard"),
    BOOK("Book"),
    CERTIFICATE("Certificate"),
    PHOTO("Photo"),
}

/** Enhancement presets (SDS 12). */
enum class ScanFilter(val label: String) {
    ORIGINAL("Original"),
    AUTO("Auto"),
    COLOR("Colour"),
    MAGIC_COLOR("Magic colour"),
    GRAYSCALE("Greyscale"),
    BLACK_WHITE("Black & white"),
    DOCUMENT("Document"),
    PHOTO("Photo"),
}

/** Manual enhancement controls. All values are offsets from neutral in -1..1. */
data class Adjustments(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val sharpness: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
) {
    val isNeutral: Boolean
        get() = brightness == 0f && contrast == 0f && sharpness == 0f &&
            saturation == 0f && exposure == 0f

    companion object {
        val NEUTRAL = Adjustments()
    }
}

/** Normalised corner set in 0..1 image space, ordered TL, TR, BR, BL. */
data class Quad(
    val topLeft: PointN,
    val topRight: PointN,
    val bottomRight: PointN,
    val bottomLeft: PointN,
) {
    val points: List<PointN> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun scaled(width: Float, height: Float): List<Pair<Float, Float>> =
        points.map { it.x * width to it.y * height }

    companion object {
        /** The whole frame — the fallback when detection finds nothing usable. */
        val FULL = Quad(
            PointN(0f, 0f),
            PointN(1f, 0f),
            PointN(1f, 1f),
            PointN(0f, 1f),
        )

        /**
         * Builds a quad from four unordered points by sorting them into TL/TR/BR/BL.
         * OpenCV's contour output has no guaranteed winding, so this ordering step is required
         * before any homography is computed.
         */
        fun fromUnordered(points: List<PointN>): Quad? {
            if (points.size != 4) return null
            val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4f
            val tl = points.filter { it.x <= cx && it.y <= cy }.minByOrNull { it.x + it.y }
            val tr = points.filter { it.x >= cx && it.y <= cy }.minByOrNull { -it.x + it.y }
            val br = points.filter { it.x >= cx && it.y >= cy }.maxByOrNull { it.x + it.y }
            val bl = points.filter { it.x <= cx && it.y >= cy }.minByOrNull { it.x - it.y }
            if (tl == null || tr == null || br == null || bl == null) return null
            if (setOf(tl, tr, br, bl).size != 4) return null
            return Quad(tl, tr, br, bl)
        }
    }
}

/** A point in normalised 0..1 image space, so corners survive resizing and rotation. */
data class PointN(val x: Float, val y: Float) {
    fun clamped(): PointN = PointN(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** Live detection result handed from the analyser to the viewfinder. */
data class DetectionResult(
    val quad: Quad?,
    val confidence: Float,
    val isStable: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
) {
    val hasDocument: Boolean get() = quad != null

    companion object {
        val NONE = DetectionResult(null, 0f, false, 0, 0)
    }
}

/** Scanner modes offered in the viewfinder. */
enum class ScanMode(val label: String) {
    SINGLE("Single"),
    BATCH("Batch"),
    ID_CARD("ID card"),
    BOOK("Book"),
    RECEIPT("Receipt"),
}
