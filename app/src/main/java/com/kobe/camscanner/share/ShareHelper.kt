package com.kobe.camscanner.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.kobe.camscanner.core.storage.KobeStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sharing and export (SDS 27, 28).
 *
 * Kobe never uploads anything. What this class does is hand another app a temporary, read-only URI
 * through [FileProvider] — the receiving app copies it, and the original never leaves Kobe's
 * sandbox.
 *
 * WhatsApp and the system file picker get direct entry points because SDS 28 singles them out as
 * the two paths that must be effortless. Everything else goes through the standard sheet.
 */
@Singleton
class ShareHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storage: KobeStorage,
) {

    /** True when a WhatsApp build is installed, so the UI can offer the shortcut honestly. */
    fun whatsAppPackage(): String? = WHATSAPP_PACKAGES.firstOrNull { isInstalled(it) }

    /**
     * One tap to WhatsApp. Falls back to the normal sheet if the direct intent is refused, which
     * happens on some builds and would otherwise look like a dead button.
     */
    fun shareToWhatsApp(file: File, mimeType: String = MIME_PDF): Boolean {
        val target = whatsAppPackage() ?: return false
        val intent = buildSendIntent(file, mimeType).apply { setPackage(target) }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            share(file, mimeType)
            false
        }
    }

    /** The Android Sharesheet (SDS 28). */
    fun share(file: File, mimeType: String = MIME_PDF, title: String = "Share document") {
        val chooser = Intent.createChooser(buildSendIntent(file, mimeType), title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    fun shareMultiple(files: List<File>, mimeType: String = MIME_IMAGE, title: String = "Share pages") {
        if (files.isEmpty()) return
        if (files.size == 1) return share(files.first(), mimeType, title)

        val uris = ArrayList(files.map { uriFor(it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Opens the document in whatever PDF viewer the user already has. */
    fun openExternally(file: File, mimeType: String = MIME_PDF): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Builds the intent that the "Save to Files" flow completes. The caller launches this with a
     * CreateDocument contract and then copies the bytes into the returned tree URI.
     */
    fun copyToUri(source: File, destination: Uri): Boolean = try {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } != null
    } catch (t: Throwable) {
        false
    }

    /**
     * Copies a file into the share cache before handing it out, so an export always sends a stable
     * snapshot even if the user edits the document a second later.
     */
    fun stageForShare(source: File, name: String = source.name): File {
        val staged = storage.shareFile(name)
        source.copyTo(staged, overwrite = true)
        return staged
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file,
    )

    private fun buildSendIntent(file: File, mimeType: String) = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uriFor(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        const val MIME_PDF = "application/pdf"
        const val MIME_IMAGE = "image/jpeg"
        const val MIME_TEXT = "text/plain"

        /** Consumer WhatsApp first, then WhatsApp Business. */
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
