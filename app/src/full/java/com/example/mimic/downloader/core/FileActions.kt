package com.example.mimic.downloader.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/** Open / share / delete for files the app published. None of these can crash the UI. */
object FileActions {

    private const val TAG = "FileActions"

    fun mimeTypeFor(context: Context, uri: Uri, fileName: String?): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: fileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }?.let(MimeTypes::forExtension)
            ?: "*/*"

    /** Returns false if no installed app can open the file (or it no longer exists). */
    fun open(context: Context, uriString: String, fileName: String?): Boolean {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(context, uri, fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return start(context, intent)
    }

    fun share(context: Context, uriString: String, fileName: String?, title: String): Boolean {
        val uri = Uri.parse(uriString)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(context, uri, fileName)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Deletes a file the app itself created: a MediaStore row it inserted (Android 10+ lets the owner
     * delete without a prompt), a document in the user-picked SAF folder, or a legacy file shared
     * through our FileProvider. Returns false if it's already gone or can't be removed.
     */
    fun delete(context: Context, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return try {
            if (uri.scheme == "file") {
                uri.path?.let { java.io.File(it).delete() } == true
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                context.contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete $uri", e)
            false
        }
    }

    /** True if [uriString] still points at a readable file (it may have been deleted outside the app). */
    fun exists(context: Context, uriString: String): Boolean = try {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "No permission to open ${intent.data}", e)
        false
    }
}
