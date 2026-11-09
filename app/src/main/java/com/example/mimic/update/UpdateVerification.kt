package com.example.mimic.update

/**
 * The identity checks a downloaded update must pass, as one pure function so they are unit-tested
 * (UpdateVerificationTest). [UpdateManager] gathers the facts from the APK and the installed app
 * and calls this; the first failure stops the update before Android's installer is opened.
 *
 * Certificates are lowercase hex SHA-256 digests of each signer certificate.
 */
internal object UpdateVerification {

    data class Failure(val check: String, val message: String, val technical: String)

    fun check(
        installedPackage: String,
        archivePackage: String,
        archiveVersionCode: Long,
        installedVersionCode: Long,
        archiveCerts: Set<String>,
        installedCerts: Set<String>,
        trustedCert: String,
        releasesPage: String,
    ): Failure? {
        // It is this app (never another package that happens to be in the release).
        if (archivePackage != installedPackage) {
            return Failure("Wrong app", "the APK is $archivePackage, not $installedPackage.", "package mismatch")
        }
        // Newer versionCode (what Android compares) — never a downgrade or reinstall.
        if (archiveVersionCode <= installedVersionCode) {
            return Failure(
                "Not newer",
                "the APK's versionCode ($archiveVersionCode) isn't higher than the installed one ($installedVersionCode).",
                "archive $archiveVersionCode <= installed $installedVersionCode",
            )
        }
        // Authenticity: signed by exactly the pinned release certificate…
        if (trustedCert.length != 64 || archiveCerts != setOf(trustedCert.lowercase())) {
            return Failure(
                "Untrusted signature",
                "the APK isn't signed with MImic's release certificate. It was not installed.",
                "apk $archiveCerts, trusted $trustedCert",
            )
        }
        // …which is also the installed app's, or Android could not update in place.
        if (archiveCerts != installedCerts) {
            return Failure(
                "Signing certificate mismatch",
                "this installed copy is signed with a different key, so Android can't update it in place. " +
                    "Install the release once from $releasesPage (back up your data first).",
                "apk $archiveCerts, installed $installedCerts",
            )
        }
        return null
    }
}
