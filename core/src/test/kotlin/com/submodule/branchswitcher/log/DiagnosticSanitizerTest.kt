package com.submodule.branchswitcher.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {

    @Test
    fun `redacts uri remote with embedded credentials`() {
        val sanitized = sanitizeDiagnosticText("clone https://alice:s3cret@example.com/repo.git now")

        assertFalse(sanitized.contains("s3cret"))
        assertFalse(sanitized.contains("alice"))
        assertTrue(sanitized.contains("<remote:"))
        assertTrue(sanitized.startsWith("clone "))
    }

    @Test
    fun `redacts scp-style remote`() {
        val sanitized = sanitizeDiagnosticText("fetch git@github.com:org/repo.git")

        assertFalse(sanitized.contains("git@github.com"))
        assertTrue(sanitized.contains("<remote:"))
    }

    @Test
    fun `redacts bare secret assignments`() {
        assertEquals("token=<redacted>", sanitizeDiagnosticText("token=abc123"))
        assertEquals("password: <redacted>", sanitizeDiagnosticText("password: hunter2"))
        assertEquals("access_token=<redacted>", sanitizeDiagnosticText("access_token=secret"))
    }

    @Test
    fun `redacts bearer authorization preserving the separator`() {
        assertEquals("authorization=<redacted>", sanitizeDiagnosticText("authorization=Bearer abc123"))
    }
}
