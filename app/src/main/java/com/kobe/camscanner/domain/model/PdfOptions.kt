package com.kobe.camscanner.domain.model

/** Output page geometry (SDS 16). Dimensions are PDF points at 72 dpi. */
enum class PdfPageSize(val label: String, val widthPt: Float, val heightPt: Float) {
    ORIGINAL("Original", 0f, 0f),
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    LEGAL("Legal", 612f, 1008f),
    A5("A5", 419.53f, 595.28f),
    A3("A3", 841.89f, 1190.55f);

    val isOriginal: Boolean get() = this == ORIGINAL
}

/** Raster quality of the embedded page images. */
enum class PdfQuality(val label: String, val jpegQuality: Int, val maxLongEdgePx: Int) {
    LOW("Low", 55, 1240),
    STANDARD("Standard", 75, 1800),
    HIGH("High", 88, 2600),
    MAXIMUM("Maximum", 96, 4000),
}

/** File-size posture. Maps onto quality but is expressed the way users think about it. */
enum class PdfCompression(val label: String, val quality: PdfQuality) {
    SMALL("Small file", PdfQuality.LOW),
    BALANCED("Balanced", PdfQuality.STANDARD),
    HIGH_QUALITY("High quality", PdfQuality.HIGH),
}

/** PDF document metadata (SDS 17). Author defaults to empty — Kobe never invents identity. */
data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
)

/** Everything needed to render a document to PDF. */
data class PdfOptions(
    val pageSize: PdfPageSize = PdfPageSize.A4,
    val compression: PdfCompression = PdfCompression.BALANCED,
    val quality: PdfQuality? = null,
    val searchable: Boolean = true,
    val metadata: PdfMetadata = PdfMetadata(),
    val marginPt: Float = 0f,
) {
    /** Explicit quality wins; otherwise the compression preset decides. */
    val effectiveQuality: PdfQuality get() = quality ?: compression.quality
}
