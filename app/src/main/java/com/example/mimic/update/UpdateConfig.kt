package com.example.mimic.update

import com.example.mimic.BuildConfig

/** Release channels. Beta users also get stable releases; stable users never get a beta. */
enum class UpdateChannel(val label: String) { STABLE("Stable"), BETA("Beta") }

/**
 * The one place that knows where MImic releases live and what they must look like.
 * See docs/RELEASING.md for how release assets are produced.
 */
object UpdateConfig {
    val owner: String = BuildConfig.UPDATE_GITHUB_OWNER
    val repo: String = BuildConfig.UPDATE_GITHUB_REPO

    /** The edition this APK is (full / standard); an update must be the same edition. */
    val edition: String = BuildConfig.FLAVOR

    /** SHA-256 (hex) of the certificate every release must be signed with — the trust anchor. */
    val trustedCertSha256: String = BuildConfig.RELEASE_CERT_SHA256.lowercase()

    val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && trustedCertSha256.length == 64

    /** Public, unauthenticated; lists stable releases and pre-releases (not drafts). */
    val releasesApi: String get() = "https://api.github.com/repos/$owner/$repo/releases?per_page=30"
    val releasesPage: String get() = "https://github.com/$owner/$repo/releases"

    /**
     * Release assets (produced by scripts/release.ps1):
     *   MImic-v4.1.0-full.apk                 universal full edition
     *   MImic-v4.1.0-full-arm64-v8a.apk       per-ABI (also armeabi-v7a, x86_64)
     *   MImic-v4.1.0-standard.apk             standard edition
     *   SHA256SUMS.txt                        "<sha256>  <file name>" for every APK
     */
    val ASSET_PATTERN = Regex("""^MImic-v(\d+(?:\.\d+)*(?:-[0-9A-Za-z.]+?)?)-(full|standard)(?:-(arm64-v8a|armeabi-v7a|x86_64))?\.apk$""")
    const val CHECKSUM_ASSET = "SHA256SUMS.txt"

    /** Hosts the release API and its downloads may come from (GitHub redirects assets to its CDN). */
    fun isTrustedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "api.github.com" || h == "github.com" || h.endsWith(".githubusercontent.com")
    }

    /** Automatic checks run at most this often (manual checks are always allowed). */
    const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
}
