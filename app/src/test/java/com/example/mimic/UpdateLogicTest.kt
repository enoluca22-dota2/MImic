package com.example.mimic

import com.example.mimic.update.AppVersion
import com.example.mimic.update.ReleaseParser
import com.example.mimic.update.UpdateChannel
import com.example.mimic.update.UpdateConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-app updater logic (MImic 4.0) against hand-written GitHub API payloads. These prove the
 * selection and comparison rules only — NOT that a real release downloads and installs.
 */
class UpdateLogicTest {

    private val sum = "SHA256SUMS.txt"

    private fun asset(name: String, url: String = "https://github.com/enoluca22-dota2/MImic/releases/download/x/$name") =
        JSONObject().put("name", name).put("browser_download_url", url).put("size", 1234)

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, assets: List<JSONObject>) = JSONObject()
        .put("tag_name", tag).put("name", "MImic $tag").put("prerelease", pre).put("draft", draft)
        .put("body", "notes for $tag").put("html_url", "https://github.com/enoluca22-dota2/MImic/releases/tag/$tag")
        .put("assets", JSONArray(assets))

    private fun fullAssets(v: String) = listOf(
        asset("MImic-v$v-full.apk"), asset("MImic-v$v-full-arm64-v8a.apk"), asset("MImic-v$v-full-x86_64.apk"),
        asset("MImic-v$v-standard.apk"), asset(sum),
    )

    private fun json(vararg r: JSONObject) = JSONArray(r.toList()).toString()

    @Test fun versions_compare_numerically_and_prereleases_sort_lower() {
        assertTrue(AppVersion.parse("4.0.10")!! > AppVersion.parse("4.0.9")!!)
        assertTrue(AppVersion.parse("4.1.0")!! > AppVersion.parse("4.0.99")!!)
        assertTrue(AppVersion.parse("v4.2.0")!! > AppVersion.parse("4.2.0-beta.2")!!)
        assertTrue(AppVersion.parse("4.2.0-beta.10")!! > AppVersion.parse("4.2.0-beta.9")!!)
        assertNull(AppVersion.parse("latest"))
    }

    @Test fun version_code_scheme_matches_the_build_and_always_increases() {
        assertEquals(BuildConfig.VERSION_CODE, AppVersion.versionCodeFor(BuildConfig.VERSION_NAME))
        assertEquals(400_000_099, AppVersion.versionCodeFor("4.0.0"))
        assertEquals(400_100_299, AppVersion.versionCodeFor("v4.1.2"))
        // Every pre-release has its own code, ordered alpha < beta < rc < final.
        val order = listOf("4.0.0-alpha.1", "4.0.0-beta.1", "4.0.0-beta.2", "4.0.0-rc.1", "4.0.0", "4.0.1-beta.1", "4.0.1", "4.1.0")
        val codes = order.map { AppVersion.versionCodeFor(it) }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
        // The same order as the version comparison the updater uses.
        assertEquals(order.map { AppVersion.parse(it)!! }.sorted(), order.map { AppVersion.parse(it)!! })
        // The last pre-4.0 build was versionCode 19: every 4.x installs over it.
        assertTrue(AppVersion.versionCodeFor("4.0.0-alpha.1") > 19)
        assertTrue(AppVersion.versionCodeFor("20.999.999") < Int.MAX_VALUE)
    }

    @Test fun stable_channel_never_offers_a_beta() {
        val payload = json(release("v4.1.0-beta.1", pre = true, assets = fullAssets("4.1.0-beta.1")), release("v4.0.1", assets = fullAssets("4.0.1")))
        assertEquals("v4.0.1", ReleaseParser.pickLatest(payload, UpdateChannel.STABLE, "full", listOf("arm64-v8a"))!!.tag)
        assertEquals("v4.1.0-beta.1", ReleaseParser.pickLatest(payload, UpdateChannel.BETA, "full", listOf("arm64-v8a"))!!.tag)
        // A pre-release version is a beta even if someone forgot the GitHub "pre-release" flag.
        val unflagged = json(release("v4.2.0-rc.1", pre = false, assets = fullAssets("4.2.0-rc.1")))
        assertNull(ReleaseParser.pickLatest(unflagged, UpdateChannel.STABLE, "full", listOf("arm64-v8a")))
    }

    @Test fun picks_the_device_abi_then_the_universal_apk_of_the_same_edition() {
        val payload = json(release("v4.0.1", assets = fullAssets("4.0.1")))
        assertEquals("MImic-v4.0.1-full-arm64-v8a.apk", ReleaseParser.pickLatest(payload, UpdateChannel.STABLE, "full", listOf("arm64-v8a", "armeabi-v7a"))!!.apk.name)
        assertEquals("MImic-v4.0.1-full.apk", ReleaseParser.pickLatest(payload, UpdateChannel.STABLE, "full", listOf("armeabi-v7a"))!!.apk.name)
        assertEquals("MImic-v4.0.1-standard.apk", ReleaseParser.pickLatest(payload, UpdateChannel.STABLE, "standard", listOf("arm64-v8a"))!!.apk.name)
    }

    @Test fun releases_without_checksums_drafts_or_mismatched_assets_are_ignored() {
        val noSums = release("v4.0.2", assets = fullAssets("4.0.2").filter { it.getString("name") != sum })
        val draft = release("v4.0.3", draft = true, assets = fullAssets("4.0.3"))
        val staleAsset = release("v4.0.4", assets = fullAssets("4.0.1"))
        val http = release("v4.0.5", assets = listOf(asset("MImic-v4.0.5-full.apk", url = "http://evil.example/x.apk"), asset(sum)))
        val ok = release("v4.0.1", assets = fullAssets("4.0.1"))
        assertEquals("v4.0.1", ReleaseParser.pickLatest(json(noSums, draft, staleAsset, http, ok), UpdateChannel.STABLE, "full", listOf("x86_64"))!!.tag)
    }

    @Test fun malformed_payloads_never_crash() {
        assertNull(ReleaseParser.pickLatest("<html>rate limited</html>", UpdateChannel.STABLE, "full", listOf("arm64-v8a")))
        assertNull(ReleaseParser.pickLatest("[]", UpdateChannel.STABLE, "full", listOf("arm64-v8a")))
        assertNull(ReleaseParser.pickLatest("""[{"tag_name": 5}, null, {"tag_name":"v9.0.0","assets":"x"}]""", UpdateChannel.STABLE, "full", listOf("arm64-v8a")))
    }

    @Test fun checksum_lines_are_matched_exactly() {
        val sums = "${"a".repeat(64)}  MImic-v4.0.1-full.apk\n${"b".repeat(64)} *MImic-v4.0.1-full-arm64-v8a.apk\nnot a line"
        assertEquals("a".repeat(64), ReleaseParser.checksumFor(sums, "MImic-v4.0.1-full.apk"))
        assertEquals("b".repeat(64), ReleaseParser.checksumFor(sums, "MImic-v4.0.1-full-arm64-v8a.apk"))
        assertNull(ReleaseParser.checksumFor(sums, "MImic-v4.0.1-standard.apk"))
    }

    @Test fun only_github_hosts_are_trusted_and_the_certificate_is_pinned() {
        assertTrue(UpdateConfig.isTrustedHost("api.github.com"))
        assertTrue(UpdateConfig.isTrustedHost("objects.githubusercontent.com"))
        assertTrue(UpdateConfig.isTrustedHost("release-assets.githubusercontent.com"))
        assertFalse(UpdateConfig.isTrustedHost("github.com.evil.example"))
        assertFalse(UpdateConfig.isTrustedHost("githubusercontent.com.attacker.io"))
        assertEquals(64, UpdateConfig.trustedCertSha256.length)
        // The pinned certificate is the one the installed MImic is signed with (debug keystore).
        assertEquals("c03c51075a49a049387185c775626074368aa9f4220eb5ba0e74cc4a4f88087d", UpdateConfig.trustedCertSha256)
        assertTrue(UpdateConfig.isConfigured)
    }
}
