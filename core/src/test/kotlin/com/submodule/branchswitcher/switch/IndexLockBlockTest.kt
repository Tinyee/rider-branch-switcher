package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IndexLockBlockTest {

    @Test
    fun `checkpoint repository id detects a real on-disk lock without a git query`() {
        val projectRoot = Files.createTempDirectory("lock-fast-path")
        val gitDir = File(projectRoot.toFile(), ".git").also { it.mkdirs() }
        val lock = File(gitDir, "index.lock").also { it.writeText("") }
        val checkpoint = mapOf("." to CheckpointEntry("abc123", "main", repositoryId = gitDir.absolutePath))
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = error("fast path must not query git")
            override fun revParseHead(workDir: File): String? = error("fast path must not query git")
            override fun indexLockFile(workDir: File): String? = error("fast path must not query git")
            override fun localBranchExists(workDir: File, branch: String): Boolean = error("fast path must not query git")
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = error("fast path must not query git")
        }

        val blocked = findBlockingIndexLocks(projectRoot, git, listOf("."), checkpoint)

        assertEquals(listOf(IndexLockBlock(".", lock.canonicalPath)), blocked)
    }
}
