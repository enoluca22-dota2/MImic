package com.example.mimic.downloader.diagnostics

import com.example.mimic.downloader.data.analyzer.ErrorAction
import com.example.mimic.downloader.data.analyzer.toAnalysisError
import com.example.mimic.downloader.data.provider.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 4.3.1: Download Diagnostics text handling and the bot-check classification. */
class DiagnosticTextTest {

    // yt-dlp 2026.08.19's exact wording (extractor/youtube/_video.py + common.py _login_hint).
    private val botCheck = "ERROR: [youtube] jNQXAC9IVRw: Sign in to confirm you’re not a bot. Use --cookies-from-browser or " +
        "--cookies for the authentication. See  https://github.com/yt-dlp/yt-dlp/wiki/FAQ#how-do-i-pass-cookies-to-yt-dlp  for how " +
        "to manually pass cookies. Also see  https://github.com/yt-dlp/yt-dlp/wiki/Extractors#exporting-youtube-cookies  for tips"

    @Test fun bot_check_is_not_reported_as_a_login_requirement() {
        val e = ProviderException.classify(botCheck)
        assertTrue(e is ProviderException.BotCheck)
        assertFalse("must not be retried automatically", e.isRetryable)
        assertEquals(DiagCategory.BOT_CHECK, DiagCategory.of(e, botCheck))
        val info = e.toAnalysisError(null)
        assertTrue(ErrorAction.RETRY in info.actions)
        assertTrue(info.message.contains("network"))
        // Straight apostrophe variant too.
        assertTrue(ProviderException.classify("ERROR: [youtube] x: Sign in to confirm you're not a bot.") is ProviderException.BotCheck)
    }

    @Test fun real_sign_in_cases_are_still_told_apart() {
        val age = "ERROR: [youtube] x: Sign in to confirm your age. This video may be inappropriate for some users."
        val priv = "ERROR: [youtube] x: Private video. Sign in if you've been granted access to this video"
        assertEquals(DiagCategory.AUTH_AGE, DiagCategory.of(ProviderException.classify(age), age))
        assertEquals(DiagCategory.AUTH_PRIVATE, DiagCategory.of(ProviderException.classify(priv), priv))
        val limited = "ERROR: [youtube] x: This content isn’t available, try again later. The current session has been rate-limited"
        assertTrue(ProviderException.classify(limited) is ProviderException.RateLimited)
        assertEquals(DiagCategory.OK, DiagCategory.of(null, ""))
        val noFfmpeg = "ERROR: Postprocessing: ffmpeg not found. Please install or provide the path using --ffmpeg-location"
        assertEquals(DiagCategory.DEVICE_FFMPEG, DiagCategory.of(ProviderException.classify(noFfmpeg), noFfmpeg))
        assertEquals(DiagCategory.DEVICE_FFMPEG, DiagCategory.of(ProviderException.MergeUnsupported("unmerged output: media.f251.webm"), "unmerged output"))
    }

    @Test fun links_keep_only_site_path_and_media_id() {
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", DiagnosticText.link("https://www.youtube.com/watch?v=jNQXAC9IVRw&si=SECRETSHARE123&pp=abc&t=10"))
        assertEquals("https://youtu.be/jNQXAC9IVRw", DiagnosticText.link("https://youtu.be/jNQXAC9IVRw?si=AbCdEfGh12345"))
        assertEquals("https://m.youtube.com/shorts/abcDEF12345", DiagnosticText.link("https://m.youtube.com/shorts/abcDEF12345?feature=share"))
        assertEquals("https://rr3---sn-abc.googlevideo.com/…", DiagnosticText.link("https://rr3---sn-abc.googlevideo.com/videoplayback?ip=81.2.3.4&sig=XYZ&expire=1"))
        assertEquals("[link]", DiagnosticText.link("not a link"))
    }

    @Test fun log_lines_lose_secrets_and_addresses() {
        val raw = listOf(
            "[debug] Proxy map: {'https': 'http://user:pw@10.0.0.2:8080'}",
            "Cookie: SAPISID=abcdef; __Secure-3PSID=ghijkl",
            "x-goog-visitor-id: CgtXYZverylongvisitoridvalue1234567890abcdefgh",
            "Authorization: SAPISIDHASH 123_abc",
            "WARNING: [youtube] Unable to fetch PO token: po_token=MnQ3ZmFrZXRva2VuZmFrZXRva2VuZmFrZXRva2Vu",
            "connect to 2a02:810d:1a3f:9e00:1234:5678:9abc:def0 failed; also 2001:db8::1 and 192.168.1.20",
            "ERROR: [youtube] x: HTTP Error 403: Forbidden https://www.youtube.com/youtubei/v1/player?key=AIzaSyFAKEKEY&prettyPrint=false",
        ).joinToString("\n")
        val cleaned = DiagnosticText.tail(raw).joinToString("\n")
        listOf("pw@", "10.0.0.2", "abcdef", "ghijkl", "CgtXYZ", "SAPISIDHASH", "123_abc", "MnQ3Zm", "2a02:810d", "2001:db8::1", "192.168.1.20", "AIzaSy").forEach {
            assertFalse("leaked '$it' in:\n$cleaned", cleaned.contains(it))
        }
        assertTrue(cleaned.contains("HTTP Error 403"))
        assertTrue(cleaned.contains("https://www.youtube.com/youtubei/v1/player"))
    }

    @Test fun facts_are_read_from_verbose_output() {
        val stderr = """
            [debug] Command-line config: ['-J', 'https://www.youtube.com/watch?v=jNQXAC9IVRw&si=PRIVATE']
            [debug] yt-dlp version stable@2026.08.19 from yt-dlp/yt-dlp [594bd50c2] (zip)
            [debug] Python 3.12.10 (CPython aarch64 64bit) - Linux-6.6
            [debug] JS runtimes: quickjs-2025-04-26
            [youtube] Extracting URL: https://www.youtube.com/watch?v=jNQXAC9IVRw
            [youtube] jNQXAC9IVRw: Downloading webpage
            [youtube] jNQXAC9IVRw: Downloading visionos player API JSON
            [youtube] jNQXAC9IVRw: Downloading web player API JSON
            [debug] [youtube] jNQXAC9IVRw: web player response playability status: LOGIN_REQUIRED
            WARNING: [youtube] jNQXAC9IVRw: HTTP Error 403: Forbidden. Retrying (1/3)...
            $botCheck
        """.trimIndent()
        val f = DiagnosticText.facts(stderr)
        assertEquals("stable@2026.08.19", f.version)
        assertEquals(listOf("visionos", "web"), f.clients)
        assertEquals(listOf(403), f.httpStatuses)
        assertEquals(listOf("web: LOGIN_REQUIRED"), f.playability)
        assertTrue(f.errors.single().contains("not a bot"))
        assertTrue(f.environment.any { it.startsWith("JS runtimes") })
        assertEquals("[youtube] jNQXAC9IVRw: Downloading web player API JSON", f.lastStep)
        val tail = DiagnosticText.tail(stderr)
        assertFalse(tail.any { it.contains("Command-line config") || it.contains("PRIVATE") || it.contains("JS runtimes") })
        // Debug lines are dropped before classification, so -v never changes the category.
        assertFalse(DiagnosticText.withoutDebug(stderr).contains("[debug]"))
        assertTrue(ProviderException.classify(DiagnosticText.withoutDebug(stderr)) is ProviderException.BotCheck)
        assertNull(DiagnosticText.facts("").version)
    }
}
