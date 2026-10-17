package com.example.mimic.data.server

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Subsonic/OpenSubsonic authentication and URL building. Pure (no Android, no network) so it
 * is unit-testable. Auth uses salted token auth: t = md5(password + salt), never sending the
 * raw password. See https://opensubsonic.netlify.app/ .
 */
object SubsonicAuth {
    const val API_VERSION = "1.16.1"
    const val CLIENT = "MImic"

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun token(password: String, salt: String): String = md5(password + salt)

    fun randomSalt(length: Int = 12): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).joinToString("") { chars.random().toString() }
    }

    /** Common auth query params, with a fresh salt unless one is supplied (for tests). */
    fun authParams(user: String, password: String, salt: String = randomSalt()): Map<String, String> =
        linkedMapOf(
            "u" to user,
            "t" to token(password, salt),
            "s" to salt,
            "v" to API_VERSION,
            "c" to CLIENT,
            "f" to "json",
        )

    fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim().removeSuffix("/")
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        return url
    }

    /** Full REST URL: <base>/rest/<endpoint>.view?<encoded params>. */
    fun buildUrl(
        baseUrl: String,
        endpoint: String,
        auth: Map<String, String>,
        extra: Map<String, String> = emptyMap(),
    ): String {
        val query = (auth + extra).entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        return "${normalizeBaseUrl(baseUrl)}/rest/$endpoint.view?$query"
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
