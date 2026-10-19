package com.example.mimic.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.mimic.data.Song
import java.io.File

/**
 * Share the ACTUAL audio file of a song through Android's Sharesheet (Bluetooth, Quick Share,
 * WhatsApp, Telegram, e-mail…), MImic 4.1.
 *
 * - Phone songs: their own MediaStore content:// URI (no copy is made).
 * - Offline downloads: the file in MImic's private storage, exposed only through
 *   [MimicShareProvider] (a FileProvider limited to the downloads folder) under a readable name
 *   ("Artist - Title.mp3"). The private path is never revealed and no duplicate file is written.
 * - Streamed-only songs (Navidrome without a download): there is no local file, so nothing is
 *   shared — the caller offers "Download first" instead. A stream URL (which carries the
 *   server login token) is NEVER shared.
 * The receiving app gets temporary read access to that one URI only.
 */
object SongShare {

    sealed interface Target {
        /** A local file that can be shared now. */
        data class File(val uri: Uri, val mimeType: String, val displayName: String) : Target
        /** Only streamable: the user must download it first. */
        data object NeedsDownload : Target
        /** Nothing to share (no song / unknown source). */
        data object Unavailable : Target
    }

    /** Where this song's audio lives, without touching the disk for the decision (pure-ish). */
    fun target(context: Context, song: Song): Target {
        val uri = Uri.parse(song.uri)
        val name = displayName(song, extensionOf(song.uri))
        return when (uri.scheme) {
            "content" -> {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: mimeFor(extensionOf(song.uri))
                Target.File(uri, mime, name)
            }
            "file" -> {
                val f = File(uri.path ?: return Target.Unavailable)
                if (song.fileMissing || !f.isFile) return if (song.remoteId != null) Target.NeedsDownload else Target.Unavailable
                val shareUri = FileProvider.getUriForFile(context, "${context.packageName}.share", f)
                    .buildUpon().appendQueryParameter(MimicShareProvider.NAME_PARAM, name).build()
                Target.File(shareUri, mimeFor(f.extension), name)
            }
            "http", "https" -> if (song.remoteId != null) Target.NeedsDownload else Target.Unavailable
            else -> Target.Unavailable
        }
    }

    fun intent(target: Target.File, song: Song): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(target.mimeType)
            .putExtra(Intent.EXTRA_STREAM, target.uri)
            .putExtra(Intent.EXTRA_TITLE, target.displayName)
            .putExtra(Intent.EXTRA_SUBJECT, "${song.title} — ${song.artist}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData makes the read grant reach the target app picked in the Sharesheet.
        send.clipData = ClipData.newRawUri(target.displayName, target.uri)
        return Intent.createChooser(send, "Share \"${song.title}\"").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** "Artist - Title.ext" with characters other apps reject removed (pure, unit-tested). */
    fun displayName(song: Song, extension: String): String {
        val base = listOf(song.artist, song.title).filter { it.isNotBlank() && !it.startsWith("Unknown") }.joinToString(" - ").ifBlank { "Song" }
        val safe = base.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120)
        return if (extension.isBlank()) safe else "$safe.$extension"
    }

    fun extensionOf(uri: String): String =
        uri.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 }.orEmpty()

    /** Audio MIME type by extension (pure, unit-tested). */
    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav", "wave" -> "audio/wav"
        else -> "audio/*"
    }
}

/**
 * FileProvider for sharing offline downloads (res/xml/share_paths.xml limits it to the downloads
 * folder). It reports the readable name passed by [SongShare] instead of the internal file name.
 */
class MimicShareProvider : FileProvider() {
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val base = super.query(uri, projection, selection, selectionArgs, sortOrder)
        val name = uri.getQueryParameter(NAME_PARAM) ?: return base
        base.use { c ->
            val out = MatrixCursor(c.columnNames, 1)
            if (c.moveToFirst()) {
                out.addRow(Array<Any?>(c.columnCount) { i ->
                    if (c.getColumnName(i) == OpenableColumns.DISPLAY_NAME) name
                    else when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                })
            }
            return out
        }
    }

    companion object {
        const val NAME_PARAM = "name"
    }
}
