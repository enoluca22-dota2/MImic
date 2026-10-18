package com.example.mimic.data.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Reads embedded tags (title / artist / album / track / duration / artwork) from a cloud file
 * WITHOUT downloading it: Android's MediaMetadataRetriever reads through [RangeSource], which
 * fetches only the 128 KB blocks the parser asks for (the ID3/MP4/FLAC headers, typically a few
 * hundred KB at most including cover art). Files are never modified.
 */
class CloudMetadataReader(private val artDir: File) {

    data class Result(val tags: CloudAudioTags, val artUri: String?)

    /** [read] fetches `length` bytes at `offset`; [size] is the file size from the listing. */
    fun read(size: Long, readRange: (offset: Long, length: Int) -> ByteArray): Result {
        val source = RangeSource(size, readRange)
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(source)
            fun key(k: Int) = r.extractMetadata(k)?.trim()?.takeIf { it.isNotEmpty() }
            val tags = CloudAudioTags(
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                trackNo = key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                discNo = key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year = key(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
            )
            val art = r.embeddedPicture?.let { saveArt(it) }
            return Result(tags, art)
        } finally {
            runCatching { r.release() }
            source.close()
        }
    }

    /**
     * Stores artwork downscaled to <= 512 px, named by content hash so an album's 12 tracks with
     * the same embedded cover share one file.
     */
    fun saveArt(bytes: ByteArray): String? {
        val name = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) } + ".jpg"
        val file = File(artDir, name)
        if (!file.isFile) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / (sample * 2) >= 512) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            artDir.mkdirs()
            val tmp = File(artDir, "$name.part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
            if (!tmp.renameTo(file)) { tmp.delete(); return null }
        }
        return Uri.fromFile(file).toString()
    }

    /** A random-access view of a remote file built from cached 128 KB range reads. */
    private class RangeSource(private val size: Long, private val readRange: (Long, Int) -> ByteArray) : MediaDataSource() {
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
        }
        var bytesFetched = 0L
            private set

        override fun getSize(): Long = size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            var copied = 0
            var pos = position
            while (copied < length && pos < size) {
                val index = pos / BLOCK
                val block = blocks[index] ?: fetch(index)
                val inBlock = (pos - index * BLOCK).toInt()
                if (inBlock >= block.size) break
                val n = minOf(length - copied, block.size - inBlock)
                System.arraycopy(block, inBlock, buffer, offset + copied, n)
                copied += n
                pos += n
            }
            return if (copied == 0) -1 else copied
        }

        private fun fetch(index: Long): ByteArray {
            if (bytesFetched > MAX_BYTES) throw IOException("Tag read budget exceeded")
            val start = index * BLOCK
            val len = minOf(BLOCK.toLong(), size - start).toInt()
            val data = readRange(start, len)
            bytesFetched += data.size
            blocks[index] = data
            return data
        }

        override fun close() = blocks.clear()

        companion object {
            const val BLOCK = 128 * 1024
            const val MAX_BLOCKS = 24
            // Never pull more than 6 MB to read one file's tags (a huge embedded image, or a
            // parser walking a whole file) — the track then just keeps folder/file-name metadata.
            const val MAX_BYTES = 6L * 1024 * 1024
        }
    }
}
