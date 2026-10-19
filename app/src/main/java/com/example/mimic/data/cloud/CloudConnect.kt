package com.example.mimic.data.cloud

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import okio.ByteString.Companion.toByteString

/**
 * What the Music Sources screen shows while an account is being connected (MImic 3.3). Lives in
 * the ViewModel, not in composition, so a rotation or a trip through the provider's UI never
 * leaves a spinner without an owner; every path ends in [Idle] or [Failed].
 */
sealed interface CloudConnectState {
    data object Idle : CloudConnectState
    data class Connecting(val step: String) : CloudConnectState
    data class Failed(val message: String, val kind: Kind) : CloudConnectState

    enum class Kind { CANCELLED, NOT_CONFIGURED, OFFLINE, AUTH }

    companion object {
        fun failed(e: Throwable): Failed = when (e) {
            is CloudException.NotConfigured -> Failed(e.message ?: "Not set up", Kind.NOT_CONFIGURED)
            is CloudException.Offline -> Failed(e.message ?: "No internet connection", Kind.OFFLINE)
            is CloudException.Cancelled -> Failed(e.message ?: "Sign-in was cancelled", Kind.CANCELLED)
            is CloudException -> Failed(e.message ?: "Couldn't connect", Kind.AUTH)
            else -> Failed("Couldn't connect (${e.javaClass.simpleName})", Kind.AUTH)
        }
    }
}

/**
 * Google Play services status codes → what the user (and the developer reading the screen) needs
 * to know. Pure so it is unit-tested; the numbers are CommonStatusCodes / GoogleSignInStatusCodes.
 */
object GoogleAuthErrors {
    const val SIGN_IN_REQUIRED = 4
    const val NETWORK_ERROR = 7
    const val INTERNAL_ERROR = 8
    const val DEVELOPER_ERROR = 10
    const val TIMEOUT = 15
    const val CANCELED = 16
    const val SIGN_IN_FAILED = 12500
    const val SIGN_IN_CANCELLED = 12501

    fun toException(statusCode: Int, packageName: String, sha1: String?): CloudException = when (statusCode) {
        NETWORK_ERROR, TIMEOUT -> CloudException.Offline("Google couldn't be reached — check your connection and try again")
        CANCELED, SIGN_IN_CANCELLED -> CloudException.Cancelled("Google sign-in was cancelled")
        DEVELOPER_ERROR -> CloudException.NotConfigured(
            "Google rejected this app (DEVELOPER_ERROR, code 10). Google Cloud needs an Android OAuth client for " +
                "package $packageName with SHA-1 ${sha1 ?: "(unknown)"} in the same project where the Drive API and " +
                "consent screen are set up. See docs/CLOUD_SETUP.md.",
        )
        SIGN_IN_FAILED -> CloudException.NotConfigured(
            "Google sign-in failed (code 12500). Usually the OAuth consent screen is incomplete or your account " +
                "isn't listed as a test user. See docs/CLOUD_SETUP.md.",
        )
        SIGN_IN_REQUIRED -> CloudException.AuthRequired("Google needs you to sign in again")
        INTERNAL_ERROR -> CloudException.AuthRequired("Google Play services had an internal error (code 8) — try again")
        else -> CloudException.AuthRequired("Google authorization failed (status $statusCode)")
    }

    /**
     * The consent activity came back without a parsable result. RESULT_CANCELED with no status
     * is what Play services returns when the user backs out — and also, on some versions, when
     * the OAuth client doesn't match, so the message points at both.
     */
    fun noResult(packageName: String, sha1: String?): CloudException = CloudException.Cancelled(
        "Google didn't complete sign-in. If you didn't cancel, Google probably doesn't recognise this app: " +
            "register an Android OAuth client for $packageName with SHA-1 ${sha1 ?: "(unknown)"} (docs/CLOUD_SETUP.md).",
    )
}

/** The certificate this APK is actually signed with — what Google and Microsoft match against. */
object AppSignature {

    data class Fingerprints(val sha1: String, val sha256: String, val base64Sha1: String)

    fun of(context: Context): Fingerprints? = runCatching {
        val cert = certificateBytes(context) ?: return null
        fingerprints(cert)
    }.getOrNull()

    fun fingerprints(cert: ByteArray): Fingerprints {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(cert)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(cert)
        return Fingerprints(colonHex(sha1), colonHex(sha256), sha1.toByteString().base64())
    }

    fun colonHex(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    @Suppress("DEPRECATION")
    private fun certificateBytes(context: Context): ByteArray? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            // Key rotation: the history runs oldest -> current; the current signer is what counts.
            if (info.hasMultipleSigners()) info.apkContentsSigners?.firstOrNull()?.toByteArray()
            else info.signingCertificateHistory?.lastOrNull()?.toByteArray()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
    }
}
