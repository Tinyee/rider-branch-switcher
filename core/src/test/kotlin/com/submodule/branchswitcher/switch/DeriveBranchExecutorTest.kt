package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.DeriveGitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeriveBranchExecutorTest {

    private val projectRoot = java.nio.file.Files.createTempDirectory("test-derive")

    @Test
    fun `derive preflight blocks a repository with a stale index lock`() {
        val log = mutableListOf<String>()
        val lockedGit = object : DeriveGitClient {
            override fun currentBranch(workDir: File): String? = "main"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchProbe(workDir: File, branch: String): Boolean = false
            override fun dirtyProbe(workDir: File): Boolean = false
            override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun remoteUrl(workDir: File): String? = null
            override fun isGitRepo(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
        }
        val executor = DeriveBranchExecutor(
            projectRoot,
            createStringAppender { log += it },
            lockedGit,
        )

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        assertEquals(1, result.outcomes.size)
        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SKIPPED, outcome.status)
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, outcome.issue?.code)
        assertTrue(outcome.issue?.diagnostic.orEmpty().contains("/repo/.git/index.lock"))
        assertEquals(emptyMap<String, DeriveCheckpointEntry>(), result.checkpoint)
        assertTrue(log.any { it.contains("stale index.lock blocks branch creation") })
    }
}
