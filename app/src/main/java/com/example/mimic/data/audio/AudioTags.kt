package com.example.mimic.data.audio

import java.nio.charset.Charset

/**
 * The few embedded tags MImic 3.2 needs that Android's MediaMetadataRetriever doesn't expose:
 * lyrics (ID3v2 USLT, Vorbis LYRICS / UNSYNCEDLYRICS) and ReplayGain (ID3v2 TXXX, Vorbis
 * REPLAYGAIN_*). Pure Kotlin over the first bytes of a file — no Android, no I/O, unit-tested.
 * Read-only and tolerant: a malformed or unknown tag yields nulls, never an exception.
 *
 * Not covered (documented): MP4/M4A "©lyr" atoms, APE tags, ID3v2.2 (3-char frames), SYLT.
 */
data class AudioTags(
    val lyrics: String? = null,
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
) {
    companion object {
        val NONE = AudioTags()

        /** How many leading bytes a caller should read for [parse] (tags live at the start). */
        const val HEAD_BYTES = 2 * 1024 * 1024

        fun parse(head: ByteArray): AudioTags = runCatching {
            when {
                head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> parseId3(head)
                head.size >= 4 && String(head, 0, 4, Charsets.US_ASCII) == "fLaC" -> parseFlac(head)
                else -> NONE
            }
        }.getOrDefault(NONE)

        /** "−6.54 dB" / "-6,54 dB" / "+1.2" -> dB, or null when absent or implausible. */
        fun parseGainDb(raw: String?): Float? = raw
            ?.trim()
            ?.replace('−', '-')
            ?.replace(',', '.')
            ?.replace(Regex("(?i)\\s*db$"), "")
            ?.trim()
            ?.toFloatOrNull()
            ?.takeIf { it.isFinite() && it > -60f && it < 60f }

        // ---- ID3v2.3 / 2.4 ----

        private fun syncsafe(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0x7f shl 21) or (b[at + 1].toInt() and 0x7f shl 14) or
                (b[at + 2].toInt() and 0x7f shl 7) or (b[at + 3].toInt() and 0x7f)

        private fun int32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff shl 24) or (b[at + 1].toInt() and 0xff shl 16) or
                (b[at + 2].toInt() and 0xff shl 8) or (b[at + 3].toInt() and 0xff)

        private fun parseId3(b: ByteArray): AudioTags {
            val major = b[3].toInt()
            if (major != 3 && major != 4) return NONE
            val flags = b[5].toInt()
            val tagEnd = minOf(b.size, 10 + syncsafe(b, 6))
            var pos = 10
            if (flags and 0x40 != 0) { // extended header
                pos += if (major == 4) syncsafe(b, pos) else int32(b, pos) + 4
            }
            var lyrics: String? = null
            var track: Float? = null
            var album: Float? = null
            while (pos + 10 <= tagEnd) {
                val id = String(b, pos, 4, Charsets.ISO_8859_1)
                if (id[0] == '\u0000') break // padding
                val size = if (major == 4) syncsafe(b, pos + 4) else int32(b, pos + 4)
                val body = pos + 10
                if (size <= 0 || body + size > tagEnd) break
                when (id) {
                    "USLT" -> if (lyrics == null) lyrics = uslt(b, body, size)
                    "TXXX" -> txxx(b, body, size)?.let { (desc, value) ->
                        when (desc.uppercase()) {
                            "REPLAYGAIN_TRACK_GAIN" -> track = parseGainDb(value)
                            "REPLAYGAIN_ALBUM_GAIN" -> album = parseGainDb(value)
                        }
                    }
                }
                pos = body + size
            }
            return AudioTags(lyrics?.takeIf { it.isNotBlank() }, track, album)
        }

        private fun charsetOf(enc: Int): Charset = when (enc) {
            1 -> Charsets.UTF_16 // with BOM
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }

        /** Index just past the encoding-appropriate string terminator starting at [from]. */
        private fun skipTerminated(b: ByteArray, from: Int, end: Int, enc: Int): Int {
            var i = from
            if (enc == 1 || enc == 2) {
                while (i + 1 < end) { if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2; i += 2 }
            } else {
                while (i < end) { if (b[i].toInt() == 0) return i + 1; i++ }
            }
            return end
        }

        private fun decode(b: ByteArray, from: Int, end: Int, enc: Int): String =
            if (end <= from) "" else String(b, from, end - from, charsetOf(enc)).trimEnd('\u0000').trim('\uFEFF')

        private fun uslt(b: ByteArray, at: Int, size: Int): String {
            val end = at + size
            val enc = b[at].toInt()
            val textStart = skipTerminated(b, at + 4, end, enc) // encoding(1) + language(3) + descriptor
            return decode(b, textStart, end, enc)
        }

        private fun txxx(b: ByteArray, at: Int, size: Int): Pair<String, String>? {
            val end = at + size
            val enc = b[at].toInt()
            val descEnd = skipTerminated(b, at + 1, end, enc)
            val terminator = if (enc == 1 || enc == 2) 2 else 1
            val desc = decode(b, at + 1, (descEnd - terminator).coerceAtLeast(at + 1), enc)
            return desc to decode(b, descEnd, end, enc)
        }

        // ---- FLAC (Vorbis comments) ----

        private fun parseFlac(b: ByteArray): AudioTags {
            var pos = 4
            while (pos + 4 <= b.size) {
                val header = b[pos].toInt() and 0xff
                val last = header and 0x80 != 0
                val type = header and 0x7f
                val len = (b[pos + 1].toInt() and 0xff shl 16) or (b[pos + 2].toInt() and 0xff shl 8) or (b[pos + 3].toInt() and 0xff)
                val body = pos + 4
                if (body + len > b.size) break
                if (type == 4) return vorbisComments(b, body, len)
                if (last) break
                pos = body + len
            }
            return NONE
        }

        private fun le32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or (b[at + 1].toInt() and 0xff shl 8) or
                (b[at + 2].toInt() and 0xff shl 16) or (b[at + 3].toInt() and 0xff shl 24)

        private fun vorbisComments(b: ByteArray, at: Int, len: Int): AudioTags {
            val end = at + len
            var pos = at
            pos += 4 + le32(b, pos) // vendor string
            val count = le32(b, pos); pos += 4
            val fields = HashMap<String, String>()
            repeat(count) {
                if (pos + 4 > end) return@repeat
                val n = le32(b, pos); pos += 4
                if (n < 0 || pos + n > end) return@repeat
                val entry = String(b, pos, n, Charsets.UTF_8); pos += n
                val eq = entry.indexOf('=')
                if (eq > 0) fields.putIfAbsent(entry.substring(0, eq).uppercase(), entry.substring(eq + 1))
            }
            return AudioTags(
                lyrics = (fields["LYRICS"] ?: fields["UNSYNCEDLYRICS"])?.takeIf { it.isNotBlank() },
                trackGainDb = parseGainDb(fields["REPLAYGAIN_TRACK_GAIN"]),
                albumGainDb = parseGainDb(fields["REPLAYGAIN_ALBUM_GAIN"]),
            )
        }
    }
}
