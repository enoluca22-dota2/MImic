package com.example.mimic

import com.example.mimic.data.DownloadRepository
import com.example.mimic.party.ListenTogether
import com.example.mimic.party.PartyInvite
import com.example.mimic.update.UpdateVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Security regression tests (see docs/security/SECURITY_CHECKLIST.md). They pin the decisions an
 * attacker would need to undo: the updater's identity checks, safe file names, invite parsing,
 * and the manifest / backup / network policies. A failure here blocks CI and the release gate.
 */
class SecurityRegressionTest {

    // ---- Updater identity (controls 33, 34) --------------------------------------------------

    private val pin = "c03c51075a49a049387185c775626074368aa9f4220eb5ba0e74cc4a4f88087d"
    private val other = "0".repeat(64)

    private fun verify(
        pkg: String = "com.example.mimic",
        code: Long = 400_700_037,
        installedCode: Long = 400_700_036,
        certs: Set<String> = setOf(pin),
        installedCerts: Set<String> = setOf(pin),
        trusted: String = pin,
    ) = UpdateVerification.check("com.example.mimic", pkg, code, installedCode, certs, installedCerts, trusted, "https://github.com/x/y/releases")

    @Test fun a_correct_update_passes() {
        assertNull(verify())
    }

    @Test fun another_package_is_rejected() {
        assertEquals("Wrong app", verify(pkg = "com.example.mimic.evil")?.check)
    }

    @Test fun downgrades_and_reinstalls_are_rejected() {
        assertEquals("Not newer", verify(code = 400_700_035)?.check)
        assertEquals("Not newer", verify(code = 400_700_036)?.check)
    }

    @Test fun an_apk_signed_with_another_key_is_rejected() {
        assertEquals("Untrusted signature", verify(certs = setOf(other))?.check)
        // An extra signer next to the real one is not the release certificate either.
        assertEquals("Untrusted signature", verify(certs = setOf(pin, other))?.check)
        assertEquals("Untrusted signature", verify(certs = emptySet())?.check)
    }

    @Test fun a_missing_or_malformed_pin_trusts_nothing() {
        assertEquals("Untrusted signature", verify(trusted = "")?.check)
        assertEquals("Untrusted signature", verify(trusted = "c03c5107")?.check)
    }

    @Test fun an_installed_copy_with_a_different_key_is_reported_not_overwritten() {
        assertEquals("Signing certificate mismatch", verify(installedCerts = setOf(other))?.check)
    }

    @Test fun the_build_pins_the_release_certificate() {
        assertEquals(pin, BuildConfig.RELEASE_CERT_SHA256.lowercase())
        assertEquals("com.example.mimic", BuildConfig.APPLICATION_ID)
    }

    // ---- Download file names (control 28) ----------------------------------------------------

    @Test fun server_ids_cannot_escape_the_downloads_folder() {
        for (id in listOf("../../shared_prefs/x", "..", ".", "", "/etc/passwd", "a\\..\\b", "%2e%2e%2f", "id\u0000.mp3")) {
            val name = DownloadRepository.safeFileName(id)
            assertFalse(id, name.contains('/') || name.contains('\\'))
            assertFalse(id, name == "." || name == ".." || name.startsWith("."))
            assertTrue(id, name.isNotEmpty())
        }
        assertTrue(DownloadRepository.safeFileName("x".repeat(5000)).length <= 120)
    }

    @Test fun ordinary_ids_keep_their_names_so_existing_downloads_still_resolve() {
        assertEquals("3fA9bC1d2E3f4G5h6I7j8K", DownloadRepository.safeFileName("3fA9bC1d2E3f4G5h6I7j8K"))
        assertEquals("mp3", DownloadRepository.safeFileName("mp3"))
        assertEquals("al-12_x.y", DownloadRepository.safeFileName("al-12_x.y"))
    }

    // ---- Links from other apps (controls 11, 18) ---------------------------------------------

    @Test fun malicious_invite_links_are_refused() {
        assertNull(PartyInvite.parse("javascript:alert(1)"))
        assertNull(PartyInvite.parse("mimic://party/../../etc"))
        assertNull(PartyInvite.parse("file:///data/data/com.example.mimic/shared_prefs/x.xml"))
        assertNull(PartyInvite.parse("content://com.example.mimic.share/songs/x"))
        assertNull(PartyInvite.parse("mimic://evil/AB12CD"))
        // A valid code with a hostile server address keeps the code but drops the server.
        val inv = PartyInvite.parse("mimic://party/AB12CD?server=javascript%3Aalert(1)")
        assertNotNull(inv)
        assertNull(inv!!.server)
        assertNull(ListenTogether.normalizeServerUrl("file:///sdcard/x"))
        assertNull(ListenTogether.normalizeServerUrl("http://host/?redirect=evil"))
    }

    // ---- Manifest, backup and network policy (controls 9–14, 24) ------------------------------

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.androidAttr(name: String) = getAttributeNS("http://schemas.android.com/apk/res/android", name)

    private fun components(manifest: Element): List<Element> =
        listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = manifest.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }

    @Test fun only_the_launcher_and_the_media_session_are_exported() {
        val exported = listOf("src/main/AndroidManifest.xml", "src/full/AndroidManifest.xml")
            .flatMap { components(xml(it)) }
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name").substringAfterLast('.') }
            .toSet()
        assertEquals(setOf("MainActivity", "PlaybackService"), exported)
    }

    @Test fun file_providers_are_private_and_the_app_is_never_debuggable() {
        val manifest = xml("src/main/AndroidManifest.xml")
        components(manifest).filter { it.tagName == "provider" }.forEach {
            assertEquals(it.androidAttr("name"), "false", it.androidAttr("exported"))
        }
        val app = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("", app.androidAttr("debuggable"))
        assertEquals("@xml/network_security_config", app.androidAttr("networkSecurityConfig"))
        assertEquals("", app.androidAttr("usesCleartextTraffic"))
    }

    @Test fun sign_ins_are_excluded_from_cloud_backup_and_device_transfer() {
        val secrets = listOf("datastore/music_server.preferences_pb", "bitchord_auth.xml", "bitchord_auth_plain.xml", "mimic_listen_together.xml", "app_webview/")
        val legacy = File("src/main/res/xml/backup_rules.xml").readText()
        val modern = xml("src/main/res/xml/data_extraction_rules.xml")
        for (s in secrets) assertTrue("backup_rules.xml must exclude $s", legacy.contains("path=\"$s\""))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val el = modern.getElementsByTagName(section).item(0) as Element
            val excluded = el.getElementsByTagName("exclude").let { n -> (0 until n.length).map { (n.item(it) as Element).getAttribute("path") } }
            for (s in secrets) assertTrue("$section must exclude $s", s in excluded)
        }
    }

    @Test fun https_only_services_can_never_be_reached_over_http() {
        val nsc = xml("src/main/res/xml/network_security_config.xml")
        val strict = nsc.getElementsByTagName("domain-config").let { n -> (0 until n.length).map { n.item(it) as Element } }
            .filter { it.getAttribute("cleartextTrafficPermitted") == "false" }
            .flatMap { dc -> dc.getElementsByTagName("domain").let { n -> (0 until n.length).map { n.item(it).textContent.trim() } } }
        for (d in listOf("github.com", "api.github.com", "githubusercontent.com", "googleapis.com", "lrclib.net")) {
            assertTrue("$d must be HTTPS-only", d in strict)
        }
        // Only the system's CAs: no user-installed CA can intercept MImic's HTTPS.
        assertFalse(File("src/main/res/xml/network_security_config.xml").readText().contains("src=\"user\""))
    }
}
