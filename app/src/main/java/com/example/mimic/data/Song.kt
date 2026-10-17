package com.example.mimic.data

/**
 * A single track from the device's media library.
 *
 * URIs are kept as [String]s (not android.net.Uri) so the model and all library-query
 * logic are plain Kotlin and unit-testable on the JVM. Media3 and Coil both accept
 * String URIs directly.
 */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String?,
    val dateAddedSec: Long,
    val folder: String = "",
    val genre: String? = null,
)
