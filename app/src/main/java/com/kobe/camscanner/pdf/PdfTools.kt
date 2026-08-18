package com.kobe.camscanner.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.kobe.camscanner.core.common.IoDispatcher
import com.kobe.camscanner.core.storage.ImageStore
import com.kobe.camscanner.core.storage.KobeStorage
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PDF manipulation (SDS 18): merge, split, extract, reorder, rotate, delete, compress.
 *
 * Everything here is local file work. Reading uses PDFBox for structure-preserving operations and
 * the platform's own [PdfRenderer] for rasterising, which is the cheapest way to turn an imported
 * PDF into pages the scanner pipeline can treat like any other image.
 */
@Singleton
class PdfTools @Inject constructor(
    private val storage: KobeStorage,
    private val imageStore: ImageStore,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /** Concatenates [sources] in order into [target]. */
    suspend fun merge(sources: List<File>, target: File): File = withContext(dispatcher) {
        require(sources.size >= 2) { "Merging needs at least two files" }
        target.parentFile?.mkdirs()
        val merger = PDFMergerUtility().apply { destinationFileName = target.absolutePath }
        sources.forEach { merger.addSource(it) }
        // Streams the pages instead of holding every source document in memory at once, which is
        // what lets a mid-range phone merge a dozen scans without an OOM.
        merger.mergeDocuments(null)
        target
    }

    /**
     * Splits [source] at the given 1-based page boundaries.
     * `splitAfter = [3, 7]` on a 10-page document yields 1-3, 4-7, 8-10.
     */
    suspend fun split(source: File, splitAfter: List<Int>, outputDir: File): List<File> =
        withContext(dispatcher) {
            outputDir.mkdirs()
            PDDocument.load(source).use { document ->
                val total = document.numberOfPages
                val bounds = (listOf(0) + splitAfter.filter { it in 1 until total }.sorted() + listOf(total))
                    .distinct()
                val baseName = source.nameWithoutExtension

                bounds.zipWithNext().mapIndexedNotNull { index, (start, end) ->
                    if (end <= start) return@mapIndexedNotNull null
                    val part = PDDocument()
                    try {
                        for (page in start until end) part.addPage(document.getPage(page))
                        val target = File(outputDir, "$baseName - part ${index + 1}.pdf")
                        part.save(target)
                        target
                    } finally {
                        part.close()
                    }
                }
            }
        }

    /** Writes the given 1-based page numbers into a new document. */
    suspend fun extractPages(source: File, pageNumbers: List<Int>, target: File): File =
        withContext(dispatcher) {
            target.parentFile?.mkdirs()
            PDDocument.load(source).use { document ->
                val output = PDDocument()
                try {
                    pageNumbers.filter { it in 1..document.numberOfPages }
                        .forEach { output.addPage(document.getPage(it - 1)) }
                    output.save(target)
                } finally {
                    output.close()
                }
            }
            target
        }

    /** Rewrites [source] with pages in the order given by [order] (0-based indices). */
    suspend fun reorder(source: File, order: List<Int>, target: File): File = withContext(dispatcher) {
        PDDocument.load(source).use { document ->
            val pages: List<PDPage> = order.filter { it in 0 until document.numberOfPages }
                .map { document.getPage(it) }
            val output = PDDocument()
            try {
                pages.forEach { output.addPage(it) }
                output.save(target)
            } finally {
                output.close()
            }
        }
        target
    }

    /** Rotates one page by a multiple of 90 degrees, in place. */
    suspend fun rotatePage(file: File, pageIndex: Int, degrees: Int): File = withContext(dispatcher) {
        PDDocument.load(file).use { document ->
            if (pageIndex in 0 until document.numberOfPages) {
                val page = document.getPage(pageIndex)
                page.rotation = ((page.rotation + degrees) % 360 + 360) % 360
                document.save(file)
            }
        }
        file
    }

    suspend fun deletePages(source: File, pageIndices: Set<Int>, target: File): File =
        withContext(dispatcher) {
            PDDocument.load(source).use { document ->
                val keep = (0 until document.numberOfPages).filterNot { it in pageIndices }
                val output = PDDocument()
                try {
                    keep.forEach { output.addPage(document.getPage(it)) }
                    output.save(target)
                } finally {
                    output.close()
                }
            }
            target
        }

    /**
     * Re-renders a PDF at a lower raster quality to shrink it (SDS 18: "Compress PDF").
     *
     * This is a rasterising compressor, which is the right kind for Kobe: its documents are page
     * photographs, so there is no vector content or embedded font to lose, and re-encoding the
     * images is where all the savings are.
     */
    suspend fun compress(
        source: File,
        target: File,
        builder: PdfBuilder,
        options: com.kobe.camscanner.domain.model.PdfOptions,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(dispatcher) {
        val pageImages = rasterise(source, dpi = 150)
        try {
            builder.build(
                pages = pageImages,
                target = target,
                // A compression pass never re-runs OCR, so any existing text layer is dropped;
                // the caller re-attaches one if the user asked for a searchable result.
                options = options.copy(searchable = false),
                onProgress = onProgress,
            )
        } finally {
            pageImages.forEach { it.delete() }
        }
        target
    }

    /** Page count without loading the whole document into PDFBox. */
    suspend fun pageCount(file: File): Int = withContext(dispatcher) {
        val renderer = openRenderer(file) ?: return@withContext 0
        try {
            renderer.pageCount
        } finally {
            renderer.close()
        }
    }

    /**
     * Rasterises every page to a JPEG in the scan store, so an imported PDF can go through exactly
     * the same enhancement and OCR path as a camera capture (SDS 26).
     */
    suspend fun rasterise(
        source: File,
        dpi: Int = 200,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<File> = withContext(dispatcher) {
        val renderer = openRenderer(source) ?: return@withContext emptyList()
        val outputDir = File(storage.scans, "import-" + System.currentTimeMillis()).apply { mkdirs() }
        val results = ArrayList<File>(renderer.pageCount)

        try {
            for (index in 0 until renderer.pageCount) {
                val page = renderer.openPage(index)
                try {
                    // PdfRenderer measures pages in points; scale to the requested dpi.
                    val scale = dpi / 72f
                    val width = (page.width * scale).toInt().coerceIn(1, MAX_RASTER_EDGE)
                    val height = (page.height * scale).toInt().coerceIn(1, MAX_RASTER_EDGE)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // PdfRenderer composites onto transparency; scans must land on white or the
                    // JPEG encoder turns the background black.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val target = File(outputDir, "page-%03d.jpg".format(index + 1))
                    imageStore.writeJpeg(bitmap, target, quality = 92)
                    bitmap.recycle()
                    results += target
                } finally {
                    page.close()
                }
                onProgress(index + 1, renderer.pageCount)
            }
        } finally {
            renderer.close()
        }
        results
    }

    private fun openRenderer(file: File): PdfRenderer? = runCatching {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    }.getOrNull()

    private companion object {
        /** Guards against a malformed PDF declaring an absurd page size. */
        const val MAX_RASTER_EDGE = 4000
    }
}
