package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DeriveBranchRunnerTest {
    @Test
    fun `cancellation after branch creation rolls back in a fresh operation`() = runBlocking {
        val root = Files.createTempDirectory("derive-runner-cancel")
        val git = RecordingDeriveGit()
        val runner = DeriveBranchRunner(
            projectRoot = root,
            operations = TestGitOperationRunner(git, TestOperationCompletion.CANCEL_AFTER),
        )
        val logs = mutableListOf<String>()

        val result = runner.execute(
            title = "Deriving",
            preset = Preset("main", "main"),
            branchName = "feature",
            log = createStringAppender(logs::add),
        )

        assertTrue(result.cancelled)
        assertTrue(result.rollbackFailures.isEmpty())
        assertEquals("main", git.currentBranch)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
        assertEquals(1, git.cancelCount)
        assertTrue(result.operationId.matches(Regex("derive-[0-9a-f]{8}")))
        assertTrue(logs.any { it.contains("[${result.operationId}] operation started: root=") })
        assertTrue(logs.any { it.contains("[${result.operationId}] baseline target: path=., branch=main") })
        assertTrue(logs.any { it.contains("[${result.operationId}] operation finished: cancelled=true") })
    }

    private class RecordingDeriveGit : GitClient {
        var currentBranch = "main"
        var openCount = 0
        var closeCount = 0
        var cancelCount = 0

        override fun openOperation(): GitOperationSession {
            openCount++
            val delegate = this
            return object : GitOperationSession, GitWorkflowClient by delegate {
                override fun cancel() {
                    cancelCount++
                }

                override fun close() {
                    closeCount++
                }
            }
        }

        override fun isGitRepo(workDir: File): Boolean = true
        override fun currentBranch(workDir: File): String = currentBranch
        override fun revParseHead(workDir: File): String = "abc123"
        override fun repositoryIdentity(workDir: File): RepositoryIdentity =
            RepositoryIdentity(File(workDir, ".git").absolutePath, null)
        override fun remoteUrl(workDir: File): String? = null
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
        override fun resetHard(workDir: File, revision: String): GitResult = ok("reset")
        override fun cancel() = Unit
        override fun dirtyProbe(workDir: File): Boolean = false
        override fun localBranchProbe(workDir: File, branch: String): Boolean = branch == currentBranch

        override fun checkoutNewBranch(workDir: File, branch: String): GitResult {
            currentBranch = branch
            return ok("checkout -b")
        }

        override fun checkoutExisting(workDir: File, branch: String): GitResult {
            currentBranch = branch
            return ok("checkout")
        }

        override fun deleteBranch(workDir: File, branch: String): GitResult = ok("branch -d")

        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch == currentBranch
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        override fun stash(workDir: File, message: String): GitResult = ok("stash")
        override fun stashPop(workDir: File, oid: String): GitResult = ok("stash pop")
        override fun fetch(workDir: File): GitResult = ok("fetch")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = ok("checkout remote")
        override fun pullFf(workDir: File, branch: String): GitResult = ok("pull")
        override fun submoduleSync(gitRoot: File): GitResult = ok("submodule sync")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = ok("submodule init")
        override fun listAllBranches(workDir: File): List<String> = listOf(currentBranch)
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()

        private fun ok(command: String) = GitResult(command, 0, "", "")
    }
}
