package com.example.mimic

import com.example.mimic.data.server.SubsonicAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicAuthTest {

    @Test fun token_matches_official_subsonic_vector() {
        // From the Subsonic API docs: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", SubsonicAuth.token("sesame", "c19b2d"))
    }

    @Test fun normalizeBaseUrl_adds_scheme_and_trims_slash() {
        assertEquals("http://192.168.1.20:4533", SubsonicAuth.normalizeBaseUrl("192.168.1.20:4533/"))
        assertEquals("https://music.example.com", SubsonicAuth.normalizeBaseUrl("https://music.example.com"))
    }

    @Test fun buildUrl_has_rest_path_and_required_params() {
        val auth = SubsonicAuth.authParams("alice", "sesame", salt = "c19b2d")
        val url = SubsonicAuth.buildUrl("http://host:4533", "ping", auth, mapOf("id" to "7"))
        assertTrue(url.startsWith("http://host:4533/rest/ping.view?"))
        assertTrue(url.contains("u=alice"))
        assertTrue(url.contains("t=26719a1196d2a940705a59634eb18eab"))
        assertTrue(url.contains("s=c19b2d"))
        assertTrue(url.contains("v=" + SubsonicAuth.API_VERSION))
        assertTrue(url.contains("c=MImic"))
        assertTrue(url.contains("f=json"))
        assertTrue(url.contains("id=7"))
    }

    @Test fun password_is_never_in_the_url() {
        val auth = SubsonicAuth.authParams("alice", "sesame", salt = "c19b2d")
        val url = SubsonicAuth.buildUrl("http://host:4533", "ping", auth)
        assertTrue(!url.contains("sesame"))
    }
}
