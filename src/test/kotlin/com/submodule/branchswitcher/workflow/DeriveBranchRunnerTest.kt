package com.submodule.branchswitcher.workflow

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

class DeriveBranchRunnerTest {
    private val project = proxy<Project>()
    private val indicator = proxy<ProgressIndicator>()

    @Test
    fun `cancellation after branch creation rolls back in a fresh operation`() = runBlocking {
        val root = Files.createTempDirectory("derive-runner-cancel")
        val git = RecordingDeriveGit()
        val runner = DeriveBranchRunner(project, root, git, cancelAfterRunTaskRunner())

        val result = runner.execute(
            title = "Deriving",
            preset = Preset("main", "main"),
            branchName = "feature",
            log = createStringAppender {},
        )

        assertTrue(result.cancelled)
        assertTrue(result.rollbackFailures.isEmpty())
        assertEquals("main", git.currentBranch)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
        assertEquals(1, git.cancelCount)
    }

    private fun cancelAfterRunTaskRunner(): TaskBridge.TaskRunner = object : TaskBridge.TaskRunner {
        override fun run(
            project: Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        ) {
            onRun(indicator)
            onCancel()
            onFinished()
        }
    }

    private inline fun <reified T> proxy(): T {
        return Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Double.TYPE -> 0.0
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as T
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
        override fun stashPop(workDir: File): GitResult = ok("stash pop")
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
