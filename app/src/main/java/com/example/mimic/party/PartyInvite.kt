/*
 * Part of MImic (GPL-3.0). Invite-link shapes adapted from BitChord's JamInviteLink.kt
 * (Copyright (C) BitChord contributors, GPL-3.0).
 */
package com.example.mimic.party

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** A party invite: the code and (when the link carries it) the server that hosts the party. */
data class PartyInvite(val code: String, val server: String?) {
    companion object {
        /**
         * Reads `mimic://party/CODE?server=URL`, `mimic://party?code=CODE&server=URL` and the web
         * form `http(s)://<party server>/invite/CODE` (then the server is the link's own origin).
         * A bare six-character code is also accepted. Null for anything else.
         */
        fun parse(value: String?): PartyInvite? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            ListenTogether.cleanCode(raw)?.takeIf { raw.length <= 8 }?.let { return PartyInvite(it, null) }
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val server = query(uri.rawQuery, "server")?.let(ListenTogether::normalizeServerUrl)
            if (scheme == "mimic" && uri.host.equals("party", ignoreCase = true)) {
                val candidate = uri.path.orEmpty().trim('/').ifBlank { query(uri.rawQuery, "code").orEmpty() }
                return ListenTogether.cleanCode(candidate)?.let { PartyInvite(it, server) }
            }
            if (scheme == "http" || scheme == "https") {
                val m = INVITE_PATH.matchEntire(uri.path.orEmpty()) ?: return null
                val origin = ListenTogether.normalizeServerUrl("$scheme://${uri.rawAuthority}") ?: return null
                return ListenTogether.cleanCode(m.groupValues[1])?.let { PartyInvite(it, server ?: origin) }
            }
            return null
        }

        /** The web link (opens a page with "Join in MImic"; works for friends without the app too). */
        fun webLink(code: String, server: String): String = "${server.trimEnd('/')}/invite/${code.uppercase()}"

        /** The app link, opened by MImic directly. */
        fun appLink(code: String, server: String): String =
            "mimic://party/${code.uppercase()}?server=" + URLEncoder.encode(server.trimEnd('/'), "UTF-8")

        private val INVITE_PATH = Regex("""/invite/([A-Za-z0-9]{6})/?""")

        private fun query(q: String?, name: String): String? = q?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it.size == 2 && it[0].equals(name, ignoreCase = true) }
            ?.let { runCatching { URLDecoder.decode(it[1], "UTF-8") }.getOrNull() }
    }
}
