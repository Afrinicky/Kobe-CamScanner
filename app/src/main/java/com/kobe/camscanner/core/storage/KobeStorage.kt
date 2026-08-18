package com.kobe.camscanner.core.storage

import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The on-device file layout from SDS 23.
 *
 * Everything lives under the app's private files directory. That is a deliberate choice: it needs
 * no storage permission at all on any API level, it is removed cleanly on uninstall, and no other
 * app can read a user's scans. Files only leave through an explicit share or export, at which point
 * they are copied into the share cache and handed out via [FileProvider].
 */
@Singleton
class KobeStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val root: File = File(context.filesDir, "Kobe").ensure()

    /** Finished, user-facing documents (the generated PDFs). */
    val documents: File = File(root, "Documents").ensure()

    /** Per-page originals and processed page images. */
    val scans: File = File(root, "Scans").ensure()

    /** Copies produced for export/share to another app. */
    val exports: File = File(root, "Exports").ensure()

    /** Saved signature strokes (SDS 31 - never leave the device). */
    val signatures: File = File(root, "Signatures").ensure()

    /** Small bitmaps backing the library grid. */
    val thumbnails: File = File(root, "Thumbnails").ensure()

    /** Soft-deleted documents awaiting purge (SDS 23). */
    val trash: File = File(root, "Trash").ensure()

    /** Short-lived files handed to other apps through FileProvider. */
    val shareCache: File = File(context.cacheDir, "share").ensure()

    fun pageDir(sessionId: String): File = File(scans, sessionId).ensure()

    fun originalFile(sessionId: String, pageId: String): File =
        File(pageDir(sessionId), "$pageId-original.jpg")

    fun processedFile(sessionId: String, pageId: String): File =
        File(pageDir(sessionId), "$pageId-processed.jpg")

    fun thumbnailFile(key: String): File = File(thumbnails, "$key.jpg")

    fun documentFile(name: String): File = File(documents, name)

    fun exportFile(name: String): File = File(exports, name)

    fun shareFile(name: String): File = File(shareCache, name)

    fun uriFor(file: File) = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )

    /** Bytes currently occupied by Kobe's own data, for the settings screen. */
    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(): Long = context.filesDir.usableSpace

    /**
     * Fails fast before a long scan session rather than half-way through writing page four.
     * 40 MB is roughly a 20-page high-quality document plus its PDF.
     */
    fun hasRoomForScan(estimatedBytes: Long = 40L * 1024 * 1024): Boolean =
        freeBytes() > estimatedBytes

    fun clearShareCache() {
        shareCache.listFiles()?.forEach { it.delete() }
    }

    /** Deletes an entire scan session directory (used after a session is abandoned). */
    fun deleteSession(sessionId: String) {
        File(scans, sessionId).deleteRecursively()
    }

    private fun File.ensure(): File = apply { if (!exists()) mkdirs() }
}
