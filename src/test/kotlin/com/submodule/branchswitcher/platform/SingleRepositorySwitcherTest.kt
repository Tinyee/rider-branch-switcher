package com.submodule.branchswitcher.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

class SingleRepositorySwitcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val project = proxy<Project>()
    private val indicator = proxy<ProgressIndicator>()

    @Test
    fun `busy result does not open a Git operation`() = runBlocking {
        val git = RecordingGit()
        val switcher = switcher(git, tryAcquireWrite = { null })

        var callbackInvoked = false
        val started = switcher.start(
            this,
            temp.newFolder("root").toPath(),
            "module",
            "dev",
        ) { callbackInvoked = true }

        assertFalse(started)
        assertFalse(callbackInvoked)
        assertEquals(0, git.openCount)
    }

    @Test
    fun `skip checks preserve not initialized dirty and current branch precedence`() = runBlocking {
        val root = temp.newFolder("root")
        val git = RecordingGit()
        var leaseCloseCount = 0
        val switcher = switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
        )

        val missing = runSwitch(switcher, root.toPath(), "module", "dev")
        val module = root.resolve("module").apply { mkdirs() }
        git.dirty = true
        val dirty = runSwitch(switcher, root.toPath(), "module", "dev")
        git.dirty = false
        git.branch = "dev"
        val current = runSwitch(switcher, root.toPath(), "module", "dev")

        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_INITIALIZED),
            missing,
        )
        assertEquals(SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.DIRTY), dirty)
        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.ALREADY_ON_TARGET),
            current,
        )
        assertTrue(module.isDirectory)
        assertEquals(3, leaseCloseCount)
    }

    @Test
    fun `local branch is preferred and checkout result is returned`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit().apply {
            localBranchExists = true
            remoteBranchExists = true
        }
        val switcher = switcher(git)

        val result = runSwitch(switcher, root.toPath(), "module", "dev")

        assertTrue(result is SingleRepositorySwitchResult.Success)
        assertEquals(1, git.checkoutExistingCount)
        assertEquals(0, git.checkoutRemoteCount)
    }

    @Test
    fun `missing branch and failed checkout remain Git failures`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit()
        val switcher = switcher(git)

        val missing = runSwitch(switcher, root.toPath(), "module", "dev")
        git.localBranchExists = true
        git.checkoutResult = GitResult("checkout", 1, "", "failed")
        val failed = runSwitch(switcher, root.toPath(), "module", "dev")

        assertTrue(missing is SingleRepositorySwitchResult.GitFailure)
        assertEquals("branch dev not found", (missing as SingleRepositorySwitchResult.GitFailure).result.stderr)
        assertTrue(failed is SingleRepositorySwitchResult.GitFailure)
        assertEquals("failed", (failed as SingleRepositorySwitchResult.GitFailure).result.stderr)
    }

    @Test
    fun `cancellation and invalid path close all acquired resources`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit()
        var leaseCloseCount = 0
        val cancelled = runSwitch(switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
            taskRunner = cancellingTaskRunner(),
        ), root.toPath(), "module", "dev")
        val invalid = runSwitch(switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
        ), root.toPath(), "../escape", "dev")

        assertEquals(SingleRepositorySwitchResult.Cancelled, cancelled)
        assertTrue(invalid is SingleRepositorySwitchResult.Unexpected)
        assertEquals(1, git.cancelCount)
        assertEquals(1, git.closeCount)
        assertEquals(2, leaseCloseCount)
    }

    private suspend fun CoroutineScope.runSwitch(
        switcher: SingleRepositorySwitcher,
        root: java.nio.file.Path,
        path: String,
        target: String,
    ): SingleRepositorySwitchResult {
        val result = CompletableDeferred<SingleRepositorySwitchResult>()
        check(switcher.start(this, root, path, target) { result.complete(it) })
        return result.await()
    }

    private fun switcher(
        git: RecordingGit,
        tryAcquireWrite: () -> AutoCloseable? = { countingLease {} },
        taskRunner: TaskBridge.TaskRunner = immediateTaskRunner(),
    ) = SingleRepositorySwitcher(
        project = project,
        gitClient = { git.provider },
        tryAcquireWrite = tryAcquireWrite,
        taskRunner = taskRunner,
    )

    private fun immediateTaskRunner(): TaskBridge.TaskRunner = object : TaskBridge.TaskRunner {
        override fun run(
            project: Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        ) {
            onRun(indicator)
            onFinished()
        }
    }

    private fun cancellingTaskRunner(): TaskBridge.TaskRunner = object : TaskBridge.TaskRunner {
        override fun run(
            project: Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        ) {
            onCancel()
            onFinished()
        }
    }

    private fun countingLease(onClose: () -> Unit) = AutoCloseable(onClose)

    private class RecordingGit {
        var dirty = false
        var branch = "main"
        var localBranchExists = false
        var remoteBranchExists = false
        var checkoutResult = GitResult("checkout", 0, "", "")
        var openCount = 0
        var closeCount = 0
        var cancelCount = 0
        var checkoutExistingCount = 0
        var checkoutRemoteCount = 0

        val provider = object : GitOperationProvider {
            override fun openOperation(): GitOperationSession {
                openCount++
                return newOperation()
            }
        }

        private fun newOperation(): GitOperationSession {
            val closed = AtomicBoolean(false)
            return Proxy.newProxyInstance(
                GitOperationSession::class.java.classLoader,
                arrayOf(GitOperationSession::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "close" -> {
                        if (closed.compareAndSet(false, true)) closeCount++
                        null
                    }
                    "cancel" -> {
                        cancelCount++
                        null
                    }
                    "isGitRepo" -> true
                    "isDirty" -> dirty
                    "currentBranch" -> branch
                    "localBranchExists" -> localBranchExists
                    "remoteBranchExists" -> remoteBranchExists
                    "checkoutExisting" -> {
                        checkoutExistingCount++
                        checkoutResult
                    }
                    "checkoutFromRemote" -> {
                        checkoutRemoteCount++
                        checkoutResult
                    }
                    else -> defaultValue(method.returnType)
                }
            } as GitOperationSession
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <reified T> proxy(): T = Proxy.newProxyInstance(
            T::class.java.classLoader,
            arrayOf(T::class.java),
        ) { _, method, _ -> defaultValue(method.returnType) } as T

        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            List::class.java -> emptyList<Any>()
            else -> null
        }
    }
}
