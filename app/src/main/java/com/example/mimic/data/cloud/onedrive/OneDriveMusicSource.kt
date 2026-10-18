package com.example.mimic.data.cloud.onedrive

import com.example.mimic.data.cloud.CloudAudioTags
import com.example.mimic.data.cloud.CloudChanges
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudFolder
import com.example.mimic.data.cloud.CloudHttp
import com.example.mimic.data.cloud.CloudItem
import com.example.mimic.data.cloud.CloudMusicSource
import com.example.mimic.data.cloud.CloudProviderType
import com.example.mimic.data.cloud.CloudTokens
import com.example.mimic.data.cloud.StreamTarget
import com.example.mimic.data.db.CloudAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * OneDrive through Microsoft Graph v1.0 (MImic 3.1).
 *
 * - Catalog: `/items/{folder}/delta`, following `@odata.nextLink` pages and storing the final
 *   `@odata.deltaLink`; a 410 (expired token / resyncRequired) falls back to a full listing.
 * - Identity: the DriveItem id — stable across rename and move.
 * - Streaming: `/items/{id}/content` answers 302 with a short-lived pre-authenticated URL. That
 *   URL gets the Range requests (Graph itself is never asked for a byte range), is cached only
 *   briefly in memory, is never stored or logged, and is re-resolved when it expires.
 */
class OneDriveMusicSource(
    private val http: CloudHttp,
    private val tokens: CloudTokens,
) : CloudMusicSource {

    override val provider = CloudProviderType.ONEDRIVE

    private val urlCache = ConcurrentHashMap<String, StreamTarget>()

    private fun base(a: CloudAccountEntity) = (a.apiBase ?: GRAPH).trimEnd('/')
    private fun tokenFn(a: CloudAccountEntity): suspend (Boolean) -> String? = { force -> tokens.token(a, force) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override suspend fun listFolders(account: CloudAccountEntity, parentId: String?): List<CloudFolder> = withContext(Dispatchers.IO) {
        val path = if (parentId == null) "/me/drive/root/children" else "/me/drive/items/${enc(parentId)}/children"
        var url: String? = base(account) + path + "?\$select=id,name,folder&\$top=200"
        val out = ArrayList<CloudFolder>()
        while (url != null) {
            val json = http.getJson(url, tokenFn(account))
            val arr = json.optJSONArray("value")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val folder = o.optJSONObject("folder") ?: continue
                out += CloudFolder(o.getString("id"), o.optString("name"), folder.optInt("childCount"))
            }
            url = json.optString("@odata.nextLink").ifBlank { null }
        }
        out.sortedBy { it.name.lowercase() }
    }

    override suspend fun changes(account: CloudAccountEntity): CloudChanges = withContext(Dispatchers.IO) {
        val folder = account.folderId ?: throw CloudException.NotConfigured("Choose a music folder first")
        val saved = account.syncToken
        if (saved != null) {
            try {
                return@withContext readDelta(account, saved, full = false)
            } catch (e: CloudException.Http) {
                // 410 Gone = the delta token expired or a resync is required: list everything again.
                if (e.code != 410) throw e
            }
        }
        readDelta(account, base(account) + "/me/drive/items/${enc(folder)}/delta?\$select=$SELECT", full = true)
    }

    private suspend fun readDelta(account: CloudAccountEntity, start: String, full: Boolean): CloudChanges {
        val items = ArrayList<CloudItem>()
        val removed = ArrayList<String>()
        var url: String? = start
        var deltaLink: String? = null
        while (url != null) {
            val json = http.getJson(url, tokenFn(account))
            val arr = json.optJSONArray("value")
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.has("deleted")) removed += o.getString("id") else items += parseItem(o)
            }
            url = json.optString("@odata.nextLink").ifBlank { null }
            if (url == null) deltaLink = json.optString("@odata.deltaLink").ifBlank { null }
        }
        return if (full) CloudChanges.Full(items, deltaLink) else CloudChanges.Incremental(items, removed, deltaLink)
    }

    override suspend fun listTree(account: CloudAccountEntity, folderId: String): List<CloudItem> = emptyList() // delta covers subtrees

    override fun resolveStream(account: CloudAccountEntity, itemId: String, forceRefresh: Boolean): StreamTarget {
        val key = account.accountKey + "/" + itemId
        if (!forceRefresh) urlCache[key]?.let { if (it.expiresAtMs > System.currentTimeMillis()) return it }
        urlCache.remove(key)
        val target = kotlinx.coroutines.runBlocking { resolveFresh(account, itemId, forceRefresh) }
        urlCache[key] = target
        return target
    }

    private suspend fun resolveFresh(account: CloudAccountEntity, itemId: String, forceToken: Boolean): StreamTarget {
        val contentUrl = base(account) + "/me/drive/items/${enc(itemId)}/content"
        val resp = http.get(contentUrl, { force -> tokens.token(account, force || forceToken) }, http.noRedirects) { it in 200..399 }
        resp.use {
            val location = it.header("Location")
            if (it.code in 300..399 && !location.isNullOrBlank()) {
                return StreamTarget(location, emptyMap(), System.currentTimeMillis() + URL_TTL_MS)
            }
        }
        // No redirect: ask for the documented short-lived download URL property instead.
        val json = http.getJson(base(account) + "/me/drive/items/${enc(itemId)}?\$select=id,@microsoft.graph.downloadUrl", tokenFn(account))
        val url = json.optString("@microsoft.graph.downloadUrl").ifBlank { null }
            ?: throw CloudException.Http(404, "OneDrive has no download URL for this file")
        return StreamTarget(url, emptyMap(), System.currentTimeMillis() + URL_TTL_MS)
    }

    override suspend fun thumbnail(account: CloudAccountEntity, itemId: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = base(account) + "/me/drive/items/${enc(itemId)}/thumbnails/0/medium/content"
        try {
            http.get(url, tokenFn(account)).use { r ->
                if (r.body?.contentType()?.type != "image") null else r.body?.bytes()
            }
        } catch (e: CloudException.Http) {
            null // 404 = no thumbnail for this file
        }
    }

    /** Drop a cached download URL (it was rejected or expired early). */
    fun invalidate(account: CloudAccountEntity, itemId: String) {
        urlCache.remove(account.accountKey + "/" + itemId)
    }

    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"
        private const val SELECT = "id,name,parentReference,file,folder,audio,size,createdDateTime,lastModifiedDateTime,cTag,eTag,deleted"
        // Graph's pre-authenticated URLs last about an hour; refresh well before that.
        private const val URL_TTL_MS = 20 * 60_000L

        fun parseItem(o: JSONObject): CloudItem {
            val file = o.optJSONObject("file")
            val audio = o.optJSONObject("audio")
            return CloudItem(
                id = o.getString("id"),
                name = o.optString("name"),
                parentId = o.optJSONObject("parentReference")?.optString("id")?.ifBlank { null },
                isFolder = o.has("folder"),
                mimeType = file?.optString("mimeType")?.ifBlank { null },
                size = o.optLong("size", 0L),
                createdAt = parseIso(o.optString("createdDateTime")),
                modifiedAt = parseIso(o.optString("lastModifiedDateTime")),
                contentVersion = o.optString("cTag").ifBlank { null } ?: o.optString("eTag").ifBlank { null },
                hash = file?.optJSONObject("hashes")?.optString("sha1Hash")?.ifBlank { null }?.let { "sha1:" + it.lowercase() },
                audio = audio?.let {
                    CloudAudioTags(
                        title = it.optString("title").ifBlank { null },
                        artist = it.optString("artist").ifBlank { null },
                        albumArtist = it.optString("albumArtist").ifBlank { null },
                        album = it.optString("album").ifBlank { null },
                        trackNo = it.optInt("track", 0).takeIf { n -> n > 0 },
                        discNo = it.optInt("disc", 0).takeIf { n -> n > 0 },
                        durationMs = it.optLong("duration", 0L),
                        genre = it.optString("genre").ifBlank { null },
                        year = it.optInt("year", 0).takeIf { n -> n > 0 },
                    )
                },
            )
        }

        fun parseIso(v: String?): Long {
            if (v.isNullOrBlank()) return 0L
            return runCatching {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
                fmt.parse(v.take(19))!!.time
            }.getOrDefault(0L)
        }
    }
}
