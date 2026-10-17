package com.example.mimic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.mimic.data.server.CredentialCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification that server passwords are encrypted at rest with the Android Keystore:
 * ciphertext differs from the plaintext, round-trips back exactly, uses a fresh IV each time, and
 * fails closed (null) on a corrupt blob rather than throwing.
 */
@RunWith(AndroidJUnit4::class)
class CredentialCipherTest {

    @Test
    fun encryptsThenDecryptsBackToOriginal() {
        val secret = "s3cr3t-Pa\$\$w0rd-ünîcödé"
        val enc = CredentialCipher.encrypt(secret)
        assertNotNull("encrypt returned null — Keystore unavailable", enc)
        assertFalse("ciphertext must not equal plaintext", enc == secret)
        assertFalse("plaintext must not appear inside the stored blob", enc!!.contains(secret))
        assertEquals("decrypt must restore the original", secret, CredentialCipher.decrypt(enc))
    }

    @Test
    fun usesFreshIvSoCiphertextDiffersEachTime() {
        val a = CredentialCipher.encrypt("same-input")
        val b = CredentialCipher.encrypt("same-input")
        assertNotNull(a); assertNotNull(b)
        assertTrue("same input should encrypt to different blobs (random IV/GCM)", a != b)
        assertEquals("same-input", CredentialCipher.decrypt(a!!))
        assertEquals("same-input", CredentialCipher.decrypt(b!!))
    }

    @Test
    fun failsClosedOnCorruptBlob() {
        assertNull("garbage must not decrypt", CredentialCipher.decrypt("not-a-real-blob!!"))
        assertNull("empty must not decrypt", CredentialCipher.decrypt(""))
    }
}
