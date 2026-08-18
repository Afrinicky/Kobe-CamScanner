package com.kobe.camscanner.share

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Printing through Android's own print framework (SDS 29).
 *
 * The adapter streams the already-generated PDF straight to the print spooler rather than
 * re-rendering it. That means the printed output is byte-for-byte the file the user shared, and any
 * printer the device supports — including wireless ones — works with no extra code.
 */
@Singleton
class PrintHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Starts the system print dialogue for [pdf].
     *
     * @return false if the device has no print service at all, which some very stripped-down
     *   Android builds genuinely lack.
     */
    fun print(pdf: File, jobName: String = pdf.nameWithoutExtension): Boolean {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: return false
        if (!pdf.exists()) return false

        printManager.print(
            jobName,
            PdfFileAdapter(pdf, jobName),
            PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setResolution(PrintAttributes.Resolution("kobe", "Kobe", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build(),
        )
        return true
    }
}

/** Hands an existing PDF file to the print spooler unchanged. */
private class PdfFileAdapter(
    private val file: File,
    private val jobName: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder("$jobName.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        // The layout never actually changes — the PDF is already paginated — so the framework is
        // told so explicitly, which skips a redundant write pass.
        callback.onLayoutFinished(info, /* changed = */ false)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            return
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (t: Throwable) {
            callback.onWriteFailed(t.message)
        }
    }
}
