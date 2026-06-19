package com.submodule.branchswitcher.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitResultTest {
    @Test
    fun `ok is true when exitCode is zero`() {
        val result = GitResult("test", 0, "", "")

        assertTrue(result.ok)
    }

    @Test
    fun `ok is false when exitCode is non-zero`() {
        val result = GitResult("test", 1, "", "error")

        assertFalse(result.ok)
        assertEquals("error", result.stderr)
    }
}
