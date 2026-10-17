package com.example.mimic.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the user's own audio files via MediaStore. This is the local analogue of the
 * catalogue endpoints a service API would expose (saved tracks, recently added, search).
 */
class MusicRepository(private val context: Context) {

    private val albumArtBase: Uri = Uri.parse("content://media/external/audio/albumart")

    suspend fun loadSongs(): List<Song> = withContext(Dispatchers.IO) {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val isQ = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val isR = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DATE_ADDED)
            if (isQ) add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME) // folder name
            if (isR) add(MediaStore.Audio.Media.GENRE) // genre, where available
        }.toTypedArray()
        // Only real music files, skip tiny clips and ringtones.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.DURATION} >= 30000"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val songs = mutableListOf<Song>()
        context.contentResolver.query(collection, projection, selection, null, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            // These may be absent on older OS versions; -1 means "not present".
            val folderCol = if (isQ) c.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME) else -1
            val genreCol = if (isR) c.getColumnIndex(MediaStore.Audio.Media.GENRE) else -1

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val albumId = c.getLong(albumIdCol)
                songs += Song(
                    id = id,
                    title = c.getString(titleCol) ?: "Unknown title",
                    artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                    album = c.getString(albumCol) ?: "Unknown album",
                    durationMs = c.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    albumArtUri = ContentUris.withAppendedId(albumArtBase, albumId).toString(),
                    dateAddedSec = c.getLong(dateAddedCol),
                    folder = folderCol.takeIf { it >= 0 }?.let { c.getString(it) } ?: "Unknown folder",
                    genre = genreCol.takeIf { it >= 0 }?.let { c.getString(it) },
                )
            }
        }
        songs
    }
}
