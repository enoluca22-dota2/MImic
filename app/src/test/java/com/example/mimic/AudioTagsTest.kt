package com.example.mimic

import com.example.mimic.data.ReplayGainMode
import com.example.mimic.data.VolumeMath
import com.example.mimic.data.audio.AudioTags
import com.example.mimic.data.audio.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** MImic 3.2: embedded lyrics / ReplayGain reading, LRC parsing, the volume equation. */
class AudioTagsTest {

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())
    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    private fun le32(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())

    private fun frame(id: String, body: ByteArray, v4: Boolean) =
        id.toByteArray(Charsets.ISO_8859_1) + (if (v4) syncsafe(body.size) else be32(body.size)) + byteArrayOf(0, 0) + body

    private fun id3(v4: Boolean, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { acc, f -> acc + f } + ByteArray(32) // + padding
        return "ID3".toByteArray() + byteArrayOf(if (v4) 4 else 3, 0, 0) + syncsafe(body.size) + body + ByteArray(100) // audio follows
    }

    private fun uslt(text: String, utf16: Boolean): ByteArray = if (utf16) {
        byteArrayOf(1) + "eng".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) + text.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }
    } else {
        byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + text.toByteArray(Charsets.UTF_8)
    }

    private fun txxx(desc: String, value: String) = byteArrayOf(3) + desc.toByteArray() + byteArrayOf(0) + value.toByteArray()

    @Test fun id3v24_lyrics_and_replaygain() {
        val tags = AudioTags.parse(
            id3(
                true,
                frame("TIT2", byteArrayOf(3) + "Song".toByteArray(), true),
                frame("TXXX", txxx("REPLAYGAIN_TRACK_GAIN", "-6.54 dB"), true),
                frame("USLT", uslt("[00:01.00]Héllo\n[00:02.50]World", utf16 = false), true),
                frame("TXXX", txxx("replaygain_album_gain", "−3,10 dB"), true),
            ),
        )
        assertEquals("[00:01.00]Héllo\n[00:02.50]World", tags.lyrics)
        assertEquals(-6.54f, tags.trackGainDb!!, 0.001f)
        assertEquals(-3.10f, tags.albumGainDb!!, 0.001f)
    }

    @Test fun id3v23_utf16_lyrics() {
        val tags = AudioTags.parse(id3(false, frame("USLT", uslt("Plain words", utf16 = true), false)))
        assertEquals("Plain words", tags.lyrics)
        assertNull(tags.trackGainDb)
    }

    @Test fun flac_vorbis_comments() {
        val comments = listOf("TITLE=x", "REPLAYGAIN_TRACK_GAIN=+1.20 dB", "UNSYNCEDLYRICS=la la")
        val block = ByteArrayOutputStream().apply {
            val vendor = "ref".toByteArray()
            write(le32(vendor.size)); write(vendor); write(le32(comments.size))
            comments.forEach { c -> val b = c.toByteArray(); write(le32(b.size)); write(b) }
        }.toByteArray()
        val streamInfo = ByteArray(34)
        val flac = "fLaC".toByteArray() +
            byteArrayOf(0, 0, 0, 34) + streamInfo +
            byteArrayOf((0x80 or 4).toByte(), (block.size shr 16).toByte(), (block.size shr 8).toByte(), block.size.toByte()) + block
        val tags = AudioTags.parse(flac)
        assertEquals("la la", tags.lyrics)
        assertEquals(1.2f, tags.trackGainDb!!, 0.001f)
    }

    @Test fun garbage_and_truncated_input_never_throw() {
        assertEquals(AudioTags.NONE, AudioTags.parse(ByteArray(0)))
        assertEquals(AudioTags.NONE, AudioTags.parse("not audio at all".toByteArray()))
        val good = id3(true, frame("USLT", uslt("x", false), true))
        assertEquals(AudioTags.NONE, AudioTags.parse(good.copyOf(14))) // header only
        assertNull(AudioTags.parseGainDb("loud"))
        assertNull(AudioTags.parseGainDb("-99 dB"))
    }

    @Test fun lrc_synced_with_multi_stamps_offset_and_meta() {
        val l = Lyrics.parse("[ar:Someone]\n[offset:500]\n[00:10.00][00:20.5]Chorus\n[00:05]Intro\n\n", "t")!!
        assertTrue(l.synced)
        assertEquals(listOf(4_500L, 9_500L, 20_000L), l.lines.map { it.timeMs })
        assertEquals(listOf("Intro", "Chorus", "Chorus"), l.lines.map { it.text })
        assertEquals(-1, l.activeIndex(1_000))
        assertEquals(1, l.activeIndex(12_000))
        assertEquals(2, l.activeIndex(99_000))
    }

    @Test fun plain_lyrics_are_unsynced_and_blank_is_null() {
        val l = Lyrics.parse("line one\nline two", "t")!!
        assertFalse(l.synced)
        assertEquals(-1, l.activeIndex(5_000))
        assertNull(Lyrics.parse("  \n ", "t"))
    }

    @Test fun volume_math() {
        assertEquals(1f, VolumeMath.replayGainFactor(null), 0f)
        assertEquals(1f, VolumeMath.replayGainFactor(+4f), 0f) // never boosts
        assertEquals(0.5f, VolumeMath.replayGainFactor(-6.0206f), 0.001f)
        assertEquals(-2f, VolumeMath.pickGain(ReplayGainMode.ALBUM, -1f, -2f))
        assertEquals(-1f, VolumeMath.pickGain(ReplayGainMode.ALBUM, -1f, null))
        assertNull(VolumeMath.pickGain(ReplayGainMode.OFF, -1f, -2f))
        // 5 s fade on a 3-minute song
        assertEquals(1f, VolumeMath.crossfadeFactor(5_000, 60_000, 180_000, 60_000), 0f)
        assertEquals(0.5f, VolumeMath.crossfadeFactor(5_000, 177_500, 180_000, 177_500), 0.001f)
        assertEquals(0.2f, VolumeMath.crossfadeFactor(5_000, 1_000, 180_000, 1_000), 0.001f)
        // too short to fade out: only the fade-in applies
        assertEquals(1f, VolumeMath.crossfadeFactor(5_000, 8_000, 9_000, 8_000), 0f)
        assertEquals(1f, VolumeMath.crossfadeFactor(0, 179_000, 180_000, 10), 0f)
    }
}
