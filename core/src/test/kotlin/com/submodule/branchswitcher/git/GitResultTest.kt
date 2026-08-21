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
    fun `classifies process capacity failures separately from command timeouts`() {
        val result = GitResult("git status", -1, "", "process capacity unavailable after 30s")

        assertEquals(GitFailureKind.PROCESS_CAPACITY, result.failureKind)
    }

    @Test
    fun `classifies every sentinel stderr via the shared constants`() {
        // Locks the cross-layer string contract: GitProcessRunner emits these exact
        // sentinels and core classifies them here. Changing a value on either side
        // breaks this test, so cancellation/timeout classification cannot silently drift.
        fun classified(stderr: String) = GitResult("git x", 1, "", stderr).failureKind
        assertEquals(GitFailureKind.NONE, GitResult("git x", 0, "", "").failureKind)
        assertEquals(GitFailureKind.CANCELLED, classified(GIT_STDERR_CANCELLED))
        assertEquals(GitFailureKind.INTERRUPTED, classified(GIT_STDERR_INTERRUPTED))
        assertEquals(GitFailureKind.TIMEOUT, classified(GIT_STDERR_TIMEOUT_PREFIX + "60s"))
        assertEquals(GitFailureKind.PROCESS_CAPACITY, classified(GIT_STDERR_CAPACITY_PREFIX + "30s"))
        assertEquals(GitFailureKind.START_FAILED, classified(GIT_STDERR_START_FAILED_PREFIX + "boom"))
        assertEquals(GitFailureKind.OUTPUT_LIMIT, classified(GIT_STDERR_OUTPUT_LIMIT_PREFIX + "cap"))
        assertEquals(GitFailureKind.OUTPUT_CAPTURE, classified(GIT_STDERR_OUTPUT_CAPTURE_PREFIX + "failed: x"))
        assertEquals(GitFailureKind.GIT_FAILED, classified("fatal: repo not found"))
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
