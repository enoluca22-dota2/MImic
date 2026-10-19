package com.example.mimic.downloader.data.provider

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.example.mimic.downloader.core.FileNaming
import com.example.mimic.downloader.data.model.DownloadCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Moves a completed yt-dlp temp file into its final, user-visible location using scoped-storage
 * safe APIs: MediaStore (API 29+, or public dirs pre-29) or a user-picked SAF tree.
 *
 * A failed copy never leaves a half-written file behind: the pending MediaStore row / SAF
 * document is deleted before the error is rethrown.
 */
class StoragePublisher(private val context: Context) {

    /** Publishes [tempFile] and returns the resulting content URI as a string. */
    suspend fun publish(
        tempFile: File,
        desiredBaseName: String,
        category: DownloadCategory,
        mimeType: String,
        customTreeUri: String?,
    ): String = withContext(Dispatchers.IO) {
        val extension = tempFile.extension.ifBlank { "bin" }
        try {
            if (customTreeUri != null) {
                publishToTree(tempFile, Uri.parse(customTreeUri), desiredBaseName, extension, mimeType)
            } else {
                ensureFreeSpace(tempFile.length())
                publishToMediaStore(tempFile, desiredBaseName, extension, category, mimeType)
            }
        } catch (e: ProviderException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to write the destination", e)
            throw ProviderException.StorageAccess(
                if (customTreeUri != null) {
                    "MImic can no longer write to the chosen download folder. Choose it again in Settings."
                } else {
                    "MImic isn't allowed to save files here. Check the app's storage permission."
                },
                e.message,
                e,
            )
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write the destination file", e)
            val message = e.message.orEmpty()
            if (message.contains("No space left", ignoreCase = true) || message.contains("ENOSPC")) {
                throw ProviderException.StorageFull(message)
            }
            throw ProviderException.StorageAccess("Saving the file failed. Check your storage and try again.", message, e)
        }
    }

    /** Where a file of [category] ends up, as shown in History ("Movies/ENAGELYUCA" or the picked folder's name). */
    fun locationLabel(category: DownloadCategory, customTreeUri: String?): String {
        if (customTreeUri != null) {
            val name = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(customTreeUri))?.name }.getOrNull()
            return name?.let { "Custom folder: $it" } ?: "Custom folder"
        }
        return relativePathFor(category)
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        // Our app-specific external dir lives on the same shared-storage volume MediaStore writes to.
        val volumePath = context.getExternalFilesDir(null)?.path ?: return
        val available = runCatching { StatFs(volumePath).availableBytes }.getOrNull() ?: return
        // Small margin so we don't fill the device to the last byte.
        if (available < requiredBytes + FREE_SPACE_MARGIN_BYTES) {
            throw ProviderException.StorageFull("need=$requiredBytes available=$available")
        }
    }

    private fun relativePathFor(category: DownloadCategory): String = when (category) {
        DownloadCategory.VIDEO -> Environment.DIRECTORY_MOVIES + "/$FOLDER"
        DownloadCategory.MUSIC -> Environment.DIRECTORY_MUSIC + "/$FOLDER"
        DownloadCategory.IMAGE -> Environment.DIRECTORY_PICTURES + "/$FOLDER"
        DownloadCategory.SUBTITLE, DownloadCategory.OTHER -> Environment.DIRECTORY_DOWNLOADS + "/$FOLDER"
    }

    private fun collectionFor(category: DownloadCategory): Uri = when (category) {
        DownloadCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        DownloadCategory.MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        DownloadCategory.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        DownloadCategory.SUBTITLE, DownloadCategory.OTHER ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }
    }

    private fun publishToMediaStore(
        tempFile: File,
        desiredBaseName: String,
        extension: String,
        category: DownloadCategory,
        mimeType: String,
    ): String {
        val displayName = FileNaming.sanitize(desiredBaseName) + ".$extension"
        val relativePath = relativePathFor(category)
        val resolver = context.contentResolver
        val collection = collectionFor(category)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore itself appends " (1)", " (2)", … if the display name is already taken.
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = resolver.insert(collection, values)
                ?: throw ProviderException.StorageAccess("Couldn't create the file in your ${relativePath.substringBefore('/')} folder.")
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(tempFile).use { it.copyTo(out) }
                } ?: throw IOException("Unable to open $itemUri for writing")
                val doneValues = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(itemUri, doneValues, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }
            tempFile.delete()
            return itemUri.toString()
        }

        // API 26-28: legacy public-directory file write (requires WRITE_EXTERNAL_STORAGE).
        @Suppress("DEPRECATION") // the only way to reach public dirs before scoped storage
        val publicDir = File(Environment.getExternalStorageDirectory(), relativePath)
        publicDir.mkdirs()
        val finalName = FileNaming.dedupeAgainstDirectory(publicDir, desiredBaseName, extension)
        val destFile = File(publicDir, finalName)
        try {
            tempFile.copyTo(destFile, overwrite = false)
        } catch (e: Exception) {
            destFile.delete()
            throw e
        }
        tempFile.delete()
        android.media.MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
        // A file:// URI can't be handed to other apps (FileUriExposedException); share it through
        // our FileProvider instead so Open/Share work on these older Android versions too.
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile).toString()
    }

    private fun publishToTree(
        tempFile: File,
        treeUri: Uri,
        desiredBaseName: String,
        extension: String,
        mimeType: String,
    ): String {
        val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?.takeIf { it.canWrite() }
            ?: throw ProviderException.StorageAccess(
                "The chosen download folder is no longer accessible. Choose it again in Settings.",
            )
        val existingNames = treeDoc.listFiles().mapNotNull { it.name }.toSet()
        val finalName = FileNaming.dedupeAgainstNames(existingNames, desiredBaseName, extension)
        val newDoc = treeDoc.createFile(mimeType, finalName)
            ?: throw ProviderException.StorageAccess("Couldn't create the file in the chosen download folder.")
        try {
            context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                FileInputStream(tempFile).use { it.copyTo(out) }
            } ?: throw IOException("Unable to open ${newDoc.uri} for writing")
        } catch (e: Exception) {
            runCatching { newDoc.delete() }
            throw e
        }
        tempFile.delete()
        return newDoc.uri.toString()
    }

    companion object {
        /** Folder (under Movies, Music, …) that new downloads are saved to — the app's name. */
        const val FOLDER = "MImic"
        private const val TAG = "StoragePublisher"
        private const val FREE_SPACE_MARGIN_BYTES = 20L * 1024 * 1024
    }
}
