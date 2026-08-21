package com.submodule.branchswitcher.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ResolveHeadAndBranchTest {

    @Test
    fun `falls back to separate reads when atomic read is unsupported`() {
        var revParseCalls = 0
        var currentBranchCalls = 0
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? {
                currentBranchCalls++
                return "dev"
            }

            override fun revParseHead(workDir: File): String? {
                revParseCalls++
                return "abc123"
            }

            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        val resolved = git.resolveHeadAndBranch(File("."))

        assertEquals("abc123", resolved?.sha)
        assertEquals("dev", resolved?.branch)
        assertEquals(1, revParseCalls)
        assertEquals(1, currentBranchCalls)
    }

    @Test
    fun `uses the atomic read and skips the fallback reads when supported`() {
        var revParseCalls = 0
        var currentBranchCalls = 0
        val git = object : GitRepositoryQuery {
            override fun headAndBranch(workDir: File): HeadAndBranch =
                HeadAndBranch("abc123", "main")

            override fun currentBranch(workDir: File): String? {
                currentBranchCalls++
                return "main"
            }

            override fun revParseHead(workDir: File): String? {
                revParseCalls++
                return "abc123"
            }

            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        val resolved = git.resolveHeadAndBranch(File("."))

        assertEquals("abc123", resolved?.sha)
        assertEquals("main", resolved?.branch)
        assertEquals(0, revParseCalls)
        assertEquals(0, currentBranchCalls)
    }

    @Test
    fun `returns null without reading the branch when the repository has no head`() {
        var currentBranchCalls = 0
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? {
                currentBranchCalls++
                return "main"
            }

            override fun revParseHead(workDir: File): String? = null

            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        assertNull(git.resolveHeadAndBranch(File(".")))
        assertEquals(0, currentBranchCalls)
    }

    @Test
    fun `a by-delegation fake honors its overrides through the shared read`() {
        // A delegate object reports a resolvable HEAD; the wrapping fake overrides
        // revParseHead to report none. The shared read must dispatch to the fake's
        // override, not to the delegate (an interface default method would forward
        // to the delegate and pair the wrong SHA with the checkpoint).
        val fakeGit = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = "main"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val missingHeadGit = object : GitRepositoryQuery by fakeGit {
            override fun revParseHead(workDir: File): String? = null
        }

        assertNull(missingHeadGit.resolveHeadAndBranch(File(".")))
    }
}
