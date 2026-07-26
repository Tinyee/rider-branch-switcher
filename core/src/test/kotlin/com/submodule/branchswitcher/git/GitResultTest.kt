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

    @Test
    fun `diagnostic classifies timeout and includes command`() {
        val result = GitResult("git fetch --prune", -1, "", "timeout after 60s")

        assertEquals(GitFailureKind.TIMEOUT, result.failureKind)
        assertEquals("[TIMEOUT] git fetch --prune (exit -1): timeout after 60s", result.diagnostic())
    }

    @Test
    fun `interrupted result has a distinct failure kind`() {
        val result = GitResult("git fetch --prune", -1, "", "interrupted")

        assertEquals(GitFailureKind.INTERRUPTED, result.failureKind)
    }

    @Test
    fun `diagnostic bounds multi-line stderr`() {
        val result = GitResult("git checkout dev", 1, "", "first\nsecond\nthird")

        assertEquals(GitFailureKind.GIT_FAILED, result.failureKind)
        assertEquals("[GIT_FAILED] git checkout dev (exit 1): first\nsecond", result.diagnostic(maxLines = 2))
    }
}
