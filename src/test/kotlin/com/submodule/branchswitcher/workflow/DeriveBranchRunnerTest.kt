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
            rollbackTitle = "Rolling back",
            preset = Preset("main", "main"),
            branchName = "feature",
            log = createStringAppender(logs::add),
        )

        assertTrue(result.cancelled)
        assertTrue(result.rollbackFailures.isEmpty())
        assertEquals("main", git.currentBranch)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
        // The fake cancels after every run: the cancelled derive task and the fresh
        // rollback task both own a session, so both sessions see a cancel.
        assertEquals(2, git.cancelCount)
        assertTrue(result.operationId.matches(Regex("derive-[0-9a-f]{8}")))
        assertTrue(logs.any { it.contains("[${result.operationId}] operation started: root=") })
        assertTrue(logs.any { it.contains("[${result.operationId}] baseline target: path=., branch=main") })
        assertTrue(logs.any { it.contains("[${result.operationId}] operation finished: cancelled=true") })
    }

    @Test
    fun `late cancellation does not repeat rollback after partial failure`() = runBlocking {
        val root = Files.createTempDirectory("derive-runner-late-cancel")
        Files.createDirectory(root.resolve("submodule"))
        val git = RecordingDeriveGit(failNewBranchDirectoryName = "submodule")
        val runner = DeriveBranchRunner(
            projectRoot = root,
            operations = TestGitOperationRunner(git, TestOperationCompletion.CANCEL_AFTER),
        )
        val logs = mutableListOf<String>()

        val result = runner.execute(
            title = "Deriving",
            rollbackTitle = "Rolling back",
            preset = Preset("main", "main", mapOf("submodule" to "main")),
            branchName = "feature",
            log = createStringAppender(logs::add),
        )

        assertTrue(result.cancelled)
        assertTrue(result.rollbackFailures.isEmpty())
        assertEquals(1, git.deleteCount)
        assertEquals(1, git.openCount)
        assertEquals(1, git.closeCount)
    }

    @Test
    fun `cancellation during rollback retries only pending paths in a fresh operation`() = runBlocking {
        val root = Files.createTempDirectory("derive-runner-rollback-cancel")
        Files.createDirectory(root.resolve("submodule"))
        val git = RecordingDeriveGit(
            failNewBranchDirectoryName = "submodule",
            cancelFirstRollbackCheckout = true,
        )
        val runner = DeriveBranchRunner(
            projectRoot = root,
            operations = TestGitOperationRunner(git, TestOperationCompletion.CANCEL_AFTER),
        )

        val result = runner.execute(
            title = "Deriving",
            rollbackTitle = "Rolling back",
            preset = Preset("main", "main", mapOf("submodule" to "main")),
            branchName = "feature",
            log = createStringAppender {},
        )

        assertTrue(result.cancelled)
        assertTrue(result.rollbackFailures.isEmpty())
        assertEquals(2, git.rollbackCheckoutCount)
        assertEquals(1, git.deleteCount)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
    }

    @Test
    fun `progress-indicator cancellation is recognized without runner cancellation`() = runBlocking {
        val root = Files.createTempDirectory("derive-runner-indicator")
        val git = RecordingDeriveGit()
        val runner = DeriveBranchRunner(
            projectRoot = root,
            operations = TestGitOperationRunner(
                git,
                TestOperationCompletion.COMPLETE,
                TestOperationProgress(isCanceled = true),
            ),
        )
        val logs = mutableListOf<String>()

        val result = runner.execute(
            title = "Deriving",
            rollbackTitle = "Rolling back",
            preset = Preset("main", "main"),
            branchName = "feature",
            log = createStringAppender(logs::add),
        )

        assertTrue("an indicator cancel must cancel the derive", result.execution?.cancelled == true)
    }

    private class RecordingDeriveGit(
        private val failNewBranchDirectoryName: String? = null,
        private val cancelFirstRollbackCheckout: Boolean = false,
    ) : GitClient {
        var currentBranch = "main"
        private val branchesByPath = mutableMapOf<String, String>()
        var openCount = 0
        var closeCount = 0
        var cancelCount = 0
        var deleteCount = 0
        var rollbackCheckoutCount = 0

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
        override fun currentBranch(workDir: File): String = branchesByPath[workDir.canonicalPath] ?: "main"
        override fun revParseHead(workDir: File): String = "abc123"
        override fun repositoryIdentity(workDir: File): RepositoryIdentity =
            if (workDir.name == failNewBranchDirectoryName) {
                RepositoryIdentity(
                    File(workDir.parentFile, ".git/modules/${workDir.name}").canonicalPath,
                    workDir.parentFile.canonicalPath,
                )
            } else {
                RepositoryIdentity(File(workDir, ".git").canonicalPath, null)
            }
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> =
            if (failNewBranchDirectoryName == null) {
                emptyList()
            } else {
                listOf(SubmoduleRegistration(failNewBranchDirectoryName, failNewBranchDirectoryName, "."))
            }
        override fun resetHard(workDir: File, revision: String): GitResult = ok("reset")
        override fun cancel() = Unit

        override fun checkoutNewBranch(workDir: File, branch: String): GitResult {
            if (workDir.name == failNewBranchDirectoryName) return GitResult("checkout -b", 1, "", "failed")
            currentBranch = branch
            branchesByPath[workDir.canonicalPath] = branch
            return ok("checkout -b")
        }

        override fun checkoutExisting(workDir: File, branch: String): GitResult {
            rollbackCheckoutCount++
            if (cancelFirstRollbackCheckout && rollbackCheckoutCount == 1) {
                return GitResult("checkout", -1, "", "cancelled")
            }
            currentBranch = branch
            branchesByPath[workDir.canonicalPath] = branch
            return ok("checkout")
        }

        override fun deleteBranch(workDir: File, branch: String): GitResult {
            deleteCount++
            return ok("branch -d")
        }

        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch == currentBranch(workDir)
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        override fun stash(workDir: File, message: String): GitResult = ok("stash")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashApply(workDir: File, oid: String): GitResult = ok("stash pop")
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
