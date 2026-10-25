package com.example.mimic.online.download

/**
 * MImic keeps lyrics itself (embedded tags or imported .lrc files), so online downloads never look
 * lyrics up. This is only the shape BitChord's [MediaTagger] accepts; MImic always passes null.
 */
internal object LyricsTag {
    class Embeddable(val plain: String?, val enhanced: String?)
}
