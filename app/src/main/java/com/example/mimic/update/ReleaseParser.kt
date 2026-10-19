package com.example.mimic.update

import org.json.JSONArray
import org.json.JSONObject

data class ReleaseAsset(val name: String, val downloadUrl: String, val sizeBytes: Long?)

/** A published GitHub release as read from the Releases API. Nothing here is invented. */
data class ReleaseInfo(
    val tag: String,
    val version: AppVersion,
    val name: String,
    /** The release body exactly as published — untrusted text, shown as plain text only. */
    val notes: String,
    val htmlUrl: String?,
    val publishedAt: String?,
    val isPreRelease: Boolean,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset,
)

/**
 * Picks the release to offer from `GET /repos/{owner}/{repo}/releases` (pure; unit-tested).
 * Release metadata is untrusted: anything malformed is skipped, never guessed. Authenticity is
 * established later from the APK's signing certificate, not from this JSON.
 */
object ReleaseParser {

    /**
     * The newest installable release for [channel] and [edition] on a device with [deviceAbis],
     * or null if there is none. A release counts only if its tag is a version, its APK asset
     * names carry that same version, and it publishes SHA256SUMS.txt.
     */
    fun pickLatest(json: String, channel: UpdateChannel, edition: String, deviceAbis: List<String>): ReleaseInfo? {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.let { o -> parseRelease(o, edition, deviceAbis) } }
            .filter { channel == UpdateChannel.BETA || !it.isPreRelease }
            .maxByOrNull { it.version }
    }

    fun parseRelease(o: JSONObject, edition: String, deviceAbis: List<String>): ReleaseInfo? {
        if (o.optBoolean("draft", false)) return null
        val tag = o.text("tag_name") ?: return null
        val version = AppVersion.parse(tag) ?: return null
        // A pre-release flag OR a pre-release version marks a beta (both must agree to be stable).
        val pre = o.optBoolean("prerelease", false) || version.isPreRelease
        val assetsJson = o.optJSONArray("assets") ?: return null
        val assets = (0 until assetsJson.length()).mapNotNull { i ->
            val a = assetsJson.optJSONObject(i) ?: return@mapNotNull null
            val name = a.text("name") ?: return@mapNotNull null
            val url = a.text("browser_download_url") ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            ReleaseAsset(name, url, a.optLong("size", -1).takeIf { it > 0 })
        }
        val apks = assets.mapNotNull { asset ->
            val m = UpdateConfig.ASSET_PATTERN.matchEntire(asset.name) ?: return@mapNotNull null
            if (AppVersion.parse(m.groupValues[1])?.compareTo(version) != 0) return@mapNotNull null
            if (m.groupValues[2] != edition) return@mapNotNull null
            m.groupValues[3].ifEmpty { null } to asset
        }
        val apk = deviceAbis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.first == abi }?.second }
            ?: apks.firstOrNull { it.first == null }?.second
            ?: return null
        val sums = assets.firstOrNull { it.name == UpdateConfig.CHECKSUM_ASSET } ?: return null
        return ReleaseInfo(
            tag = tag,
            version = version,
            name = o.text("name") ?: tag,
            notes = o.text("body").orEmpty().trim().take(MAX_NOTES),
            htmlUrl = o.text("html_url")?.takeIf { it.startsWith("https://github.com/") },
            publishedAt = o.text("published_at"),
            isPreRelease = pre,
            apk = apk,
            checksums = sums,
        )
    }

    /** [fileName]'s hash in a `sha256sum`-style file ("<hex>  <name>" or "<hex> *<name>"). */
    fun checksumFor(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim() }
        .mapNotNull { line ->
            val parts = line.split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hash = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == fileName && hash.matches(Regex("[0-9a-f]{64}"))) hash else null
        }
        .firstOrNull()

    private const val MAX_NOTES = 8_000

    private fun JSONObject.text(field: String): String? =
        if (isNull(field)) null else (opt(field) as? String)?.takeIf { it.isNotBlank() }
}
