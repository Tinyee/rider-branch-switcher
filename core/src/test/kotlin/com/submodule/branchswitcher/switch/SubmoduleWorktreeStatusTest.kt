package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubmoduleWorktreeStatusTest {

    private lateinit var root: File
    private lateinit var worktree: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("submodule-worktree-status").toFile()
        worktree = File(root, "SubA").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `main repository path is never unassociated`() {
        val identity = RepositoryIdentity(File(root, ".git").absolutePath, root.absolutePath)

        assertFalse(isUnassociatedSubmoduleWorktree(root, ".", root, identity))
    }

    @Test
    fun `missing identity is unassociated`() {
        assertTrue(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, null))
    }

    @Test
    fun `identity without a superproject root is unassociated`() {
        val identity = RepositoryIdentity(File(worktree, ".git").absolutePath, null)

        assertTrue(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, identity))
    }

    @Test
    fun `external git directory under the project is associated`() {
        val identity = RepositoryIdentity(File(root, ".git/modules/SubA").absolutePath, root.absolutePath)

        assertFalse(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, identity))
    }

    @Test
    fun `worktree with its own git directory is unassociated`() {
        val identity = RepositoryIdentity(File(worktree, ".git").absolutePath, root.absolutePath)

        assertTrue(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, identity))
    }

    @Test
    fun `git directory differing from the expected one is unassociated`() {
        val identity = RepositoryIdentity(File(root, ".git/modules/SubA").absolutePath, root.absolutePath)
        val expected = File(root, ".git/modules/Other").pathIdentity()

        assertTrue(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, identity, expected))
    }

    @Test
    fun `superproject outside the project root is unassociated`() {
        val outside = Files.createTempDirectory("submodule-worktree-outside").toFile()
        try {
            val identity = RepositoryIdentity(File(root, ".git/modules/SubA").absolutePath, outside.absolutePath)

            assertTrue(isUnassociatedSubmoduleWorktree(root, "SubA", worktree, identity))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `leftover external git directory under modules is a canonical leftover`() {
        val identity = RepositoryIdentity(File(root, ".git/modules/SubA").absolutePath, null)

        assertTrue(isCanonicalLeftoverGitDirectory(worktree, identity))
    }

    @Test
    fun `standalone worktree git directory is not a canonical leftover`() {
        val identity = RepositoryIdentity(File(worktree, ".git").absolutePath, null)

        assertFalse(isCanonicalLeftoverGitDirectory(worktree, identity))
    }

    @Test
    fun `external git directory outside modules is not a canonical leftover`() {
        val outside = Files.createTempDirectory("canonical-outside").toFile()
        try {
            val identity = RepositoryIdentity(File(outside, "data").absolutePath, null)

            assertFalse(isCanonicalLeftoverGitDirectory(worktree, identity))
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `missing identity is not a canonical leftover`() {
        assertFalse(isCanonicalLeftoverGitDirectory(worktree, null))
    }

    @Test
    fun `expected git directory for a top-level registration`() {
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun repositoryIdentity(workDir: File): RepositoryIdentity =
                RepositoryIdentity(File(root, ".git").absolutePath, null)
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        val expected = expectedSubmoduleGitDirectory(root, SubmoduleRegistration("SubA", "SubA", "."), git)

        assertEquals(File(File(root, ".git"), "modules/SubA").pathIdentity(), expected)
    }

    @Test
    fun `expected git directory for a nested registration uses the parent git dir`() {
        val parentDir = File(root, "Parent").apply { mkdirs() }
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun repositoryIdentity(workDir: File): RepositoryIdentity =
                RepositoryIdentity(File(parentDir, ".git").absolutePath, root.absolutePath)
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        val expected = expectedSubmoduleGitDirectory(root, SubmoduleRegistration("Parent/Nested", "Nested", "Parent"), git)

        assertEquals(File(File(parentDir, ".git"), "modules/Nested").pathIdentity(), expected)
    }

    @Test
    fun `missing registration or parent identity has no expected git directory`() {
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }

        assertNull(expectedSubmoduleGitDirectory(root, null, git))
        assertNull(expectedSubmoduleGitDirectory(root, SubmoduleRegistration("SubA", "SubA", "."), git))
    }
}
