package com.example.mimic.online

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineDiagnosticsTest {
    @Test fun tokens_ids_and_emails_are_masked() {
        val raw = "visitor=CgtvQmtGcGkycWZkRSjvk_jWBjIKCgJBTBIEGgAgOw%3D%3D acting as channel pageId=112233445566 me@gmail.com resolved uuZE_IRwLNI @ 141kbps"
        val out = OnlineDiagnostics.redact(raw)
        assertFalse(out.contains("CgtvQmtG"))
        assertFalse(out.contains("112233445566"))
        assertFalse(out.contains("me@gmail.com"))
        assertTrue(out.contains("uuZE_IRwLNI")) // a public video id stays readable
    }
}
