package com.example.mimic.data.cloud.google

import com.example.mimic.data.cloud.CloudChanges
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudFolder
import com.example.mimic.data.cloud.CloudHttp
import com.example.mimic.data.cloud.CloudItem
import com.example.mimic.data.cloud.CloudMusicSource
import com.example.mimic.data.cloud.CloudProviderType
import com.example.mimic.data.cloud.CloudTokens
import com.example.mimic.data.cloud.StreamTarget
import com.example.mimic.data.cloud.onedrive.OneDriveMusicSource
import com.example.mimic.data.db.CloudAccountEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Google Drive through the Drive REST API v3 (MImic 3.1).
 *
 * - Catalog: a breadth-first `files.list` of the music folder, then the Changes API from a start
 *   page token taken *before* that listing (so nothing uploaded during it is missed). Changes are
 *   drive-wide; the sync engine keeps only items inside the music folder.
 * - Streaming: `files.get?alt=media` with the bearer token in a header (never in the URL) and
 *   standard Range requests. No file is ever shared or made public.
 */
class GoogleDriveMusicSource(
    private val http: CloudHttp,
    private val tokens: CloudTokens,
) : CloudMusicSource {

    override val provider = CloudProviderType.GOOGLE_DRIVE

    private fun base(a: CloudAccountEntity) = (a.apiBase ?: DRIVE).trimEnd('/')
    private fun tokenFn(a: CloudAccountEntity): suspend (Boolean) -> String? = { force -> tokens.token(a, force) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Signed-in user's display name, email and stable permission id (needs no extra scope). */
    suspend fun about(account: CloudAccountEntity): Triple<String, String?, String> = withContext(Dispatchers.IO) {
        val u = http.getJson(base(account) + "/about?fields=" + enc("user(displayName,emailAddress,permissionId)"), tokenFn(account))
            .getJSONObject("user")
        Triple(u.optString("displayName"), u.optString("emailAddress").ifBlank { null }, u.getString("permissionId"))
    }

    override suspend fun listFolders(account: CloudAccountEntity, parentId: String?): List<CloudFolder> = withContext(Dispatchers.IO) {
        val q = "'${parentId ?: "root"}' in parents and mimeType = '$FOLDER_MIME' and trashed = false"
        list(account, q, "nextPageToken,files(id,name)").map { CloudFolder(it.getString("id"), it.optString("name")) }
            .sortedBy { it.name.lowercase() }
    }

    override suspend fun changes(account: CloudAccountEntity): CloudChanges = withContext(Dispatchers.IO) {
        val folder = account.folderId ?: throw CloudException.NotConfigured("Choose a music folder first")
        val saved = account.syncToken
        if (saved != null) {
            try {
                return@withContext readChanges(account, saved)
            } catch (e: CloudException.Http) {
                // An invalid/expired page token: start over with a full listing.
                if (e.code != 400 && e.code != 404 && e.code != 410) throw e
            }
        }
        val start = http.getJson(base(account) + "/changes/startPageToken", tokenFn(account)).getString("startPageToken")
        CloudChanges.Full(listTreeInternal(account, folder), start)
    }

    private suspend fun readChanges(account: CloudAccountEntity, token: String): CloudChanges {
        val upserts = ArrayList<CloudItem>()
        val removed = ArrayList<String>()
        var page: String? = token
        var newStart: String? = null
        while (page != null) {
            val url = base(account) + "/changes?pageToken=" + enc(page) + "&pageSize=1000&includeRemoved=true&spaces=drive" +
                "&fields=" + enc("nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS,trashed))")
            val json = http.getJson(url, tokenFn(account))
            val arr = json.optJSONArray("changes")
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val file = c.optJSONObject("file")
                if (c.optBoolean("removed") || file == null || file.optBoolean("trashed")) removed += c.getString("fileId")
                else upserts += parseFile(file)
            }
            page = json.optString("nextPageToken").ifBlank { null }
            if (page == null) newStart = json.optString("newStartPageToken").ifBlank { null }
        }
        return CloudChanges.Incremental(upserts, removed, newStart ?: token, listNewFolders = true)
    }

    override suspend fun listTree(account: CloudAccountEntity, folderId: String): List<CloudItem> =
        withContext(Dispatchers.IO) { listTreeInternal(account, folderId) }

    /** Breadth-first listing of everything under [root] (folders first-class, trash excluded). */
    private suspend fun listTreeInternal(account: CloudAccountEntity, root: String): List<CloudItem> {
        val out = ArrayList<CloudItem>()
        val queue = ArrayDeque(listOf(root))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (!seen.add(folder)) continue
            for (f in list(account, "'$folder' in parents and trashed = false", "nextPageToken,files($FILE_FIELDS)")) {
                val item = parseFile(f, parentHint = folder)
                out += item
                if (item.isFolder) queue += item.id
            }
        }
        return out
    }

    private suspend fun list(account: CloudAccountEntity, q: String, fields: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page: String? = null
        do {
            val url = base(account) + "/files?q=" + enc(q) + "&pageSize=1000&spaces=drive&fields=" + enc(fields) +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val json = http.getJson(url, tokenFn(account))
            val arr = json.optJSONArray("files")
            if (arr != null) for (i in 0 until arr.length()) out += arr.getJSONObject(i)
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null)
        return out
    }

    override fun resolveStream(account: CloudAccountEntity, itemId: String, forceRefresh: Boolean): StreamTarget {
        // The token is fetched per open (the SDK caches and renews it); never part of the URL.
        val token = tokens.token(account, forceRefresh)
        return StreamTarget(base(account) + "/files/" + enc(itemId) + "?alt=media", mapOf("Authorization" to "Bearer $token"))
    }

    companion object {
        const val DRIVE = "https://www.googleapis.com/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,mimeType,parents,size,createdTime,modifiedTime,md5Checksum,version"

        fun parseFile(o: JSONObject, parentHint: String? = null): CloudItem {
            val parents = o.optJSONArray("parents")
            val md5 = o.optString("md5Checksum").ifBlank { null }
            return CloudItem(
                id = o.getString("id"),
                name = o.optString("name"),
                parentId = parents?.optString(0)?.ifBlank { null } ?: parentHint,
                isFolder = o.optString("mimeType") == FOLDER_MIME,
                mimeType = o.optString("mimeType").ifBlank { null },
                size = o.optString("size").toLongOrNull() ?: 0L,
                createdAt = OneDriveMusicSource.parseIso(o.optString("createdTime")),
                modifiedAt = OneDriveMusicSource.parseIso(o.optString("modifiedTime")),
                contentVersion = md5 ?: o.optString("version").ifBlank { null },
                hash = md5?.let { "md5:" + it.lowercase() },
            )
        }
    }
}
