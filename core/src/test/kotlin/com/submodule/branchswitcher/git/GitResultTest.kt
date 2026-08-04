package com.submodule.branchswitcher.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitResultTest {
    @Test
    fun `diagnostic classifies timeout and includes command`() {
        val result = GitResult("git fetch --prune", -1, "", "timeout after 60s")

        assertEquals(GitFailureKind.TIMEOUT, result.failureKind)
        assertEquals("[TIMEOUT] git fetch --prune (exit -1): timeout after 60s", result.diagnostic())
    }

    @Test
    fun `diagnostic bounds multi-line stderr`() {
        val result = GitResult("git checkout dev", 1, "", "first\nsecond\nthird")

        assertEquals(GitFailureKind.GIT_FAILED, result.failureKind)
        assertEquals("[GIT_FAILED] git checkout dev (exit 1): first\nsecond", result.diagnostic(maxLines = 2))
    }

    @Test
    fun `diagnostic fingerprints remote addresses and redacts credentials`() {
        val result = GitResult(
            "git fetch https://oauth2:command-secret@example.com/private/repo.git",
            1,
            "",
            "fatal: unable to access 'https://oauth2:secret@example.com/private/repo.git': denied\n" +
                "ssh failed for git@example.org:team/internal.git and git@localhost:private.git " +
                "token=abc123 Authorization: Bearer bearer-secret",
        )

        val diagnostic = result.diagnostic()

        assertFalse(diagnostic.contains("oauth2:secret"))
        assertFalse(diagnostic.contains("command-secret"))
        assertFalse(diagnostic.contains("private/repo.git"))
        assertFalse(diagnostic.contains("team/internal.git"))
        assertFalse(diagnostic.contains("abc123"))
        assertFalse(diagnostic.contains("private.git"))
        assertFalse(diagnostic.contains("bearer-secret"))
        assertTrue(diagnostic.contains("<remote:"))
        assertTrue(diagnostic.contains("token=<redacted>"))
        assertEquals(diagnostic, GitQueryException(result).message)
    }
}
