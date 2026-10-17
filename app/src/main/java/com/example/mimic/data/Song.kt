package com.example.mimic.data

import android.net.Uri

/** A single track from the device's media library. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri?,
    val dateAddedSec: Long,
)
