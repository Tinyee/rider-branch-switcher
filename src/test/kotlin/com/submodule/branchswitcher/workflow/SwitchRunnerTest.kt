package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.IndexLockBlockedException
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.operation.OperationProgress
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.SwitchExecutionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import com.submodule.branchswitcher.switch.OperationCancelledException

class SwitchRunnerTest {
    @Test
    fun `workflow runs on the IO dispatcher independently of its caller`() = runBlocking {
        val callerThread = Thread.currentThread()
        var workflowThread: Thread? = null
        var ranOnIoDispatcher = false
        val operations = object : com.submodule.branchswitcher.operation.GitOperationRunner {
            override suspend fun <T> run(
                title: String,
                block: (com.submodule.branchswitcher.operation.OperationProgress, GitOperationSession) -> T,
            ): com.submodule.branchswitcher.operation.GitOperationResult<T> {
                workflowThread = Thread.currentThread()
                ranOnIoDispatcher = Thread.currentThread().name.startsWith("DefaultDispatcher-worker")
                return com.submodule.branchswitcher.operation.GitOperationResult.Cancelled()
            }

            override fun openOperation(): GitOperationSession = error("recovery should not start")
        }
        val runner = SwitchRunner(
            Files.createTempDirectory("switch-runner-thread"),
            operations,
        )

        runner.execute(
            "Switching",
            request(),
            createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue("workflow must run on the IO dispatcher", ranOnIoDispatcher)
        assertNotNull(workflowThread)
        assertNotSame(callerThread, workflowThread)
    }

    @Test
    fun `missing git repo returns structured execution result`() = runBlocking {
        val git = RecordingGit()
        val runner = runner(Files.createTempDirectory("switch-runner"), git)

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertFalse("missing git repo should fail through executor", result.ok)
        assertFalse(result.cancelled)
        assertNotNull("execution should be available for rollback decisions", result.execution)
    }

    @Test
    fun `cancelled operation returns cancelled result without execution`() = runBlocking {
        val git = RecordingGit()
        val runner = runner(
            Files.createTempDirectory("switch-runner-cancel"),
            git,
            TestOperationCompletion.CANCEL_BEFORE,
        )

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertNull("cancel before run should not create an execution result", result.execution)
    }

    @Test
    fun `execute returns ok when shared runner completes switch pipeline`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-ok")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun runtimeInfo(workDir: File) =
                throw IllegalStateException("version unavailable")
        }
        val runner = runner(root, git)
        val logs = mutableListOf<String>()

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender(logs::add),
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue(result.ok)
        assertFalse(result.cancelled)
        assertNotNull(result.execution)
        assertEquals(1, git.submoduleSyncCount)
        assertTrue(result.operationId.matches(Regex("switch-[0-9a-f]{8}")))
        val executionContext = "[${result.operationId}/execute]"
        assertTrue(logs.any { it.contains("$executionContext operation started: root=") })
        assertTrue(logs.any { it.contains("$executionContext options: dirty=") })
        assertTrue(logs.any { it.contains("runtime inspection failed") && it.contains("version unavailable") })
        assertTrue(logs.any { it.contains("$executionContext requested target: path=., branch=dev") })
        assertTrue(logs.any { it.contains("$executionContext [checkpoint]") })
        assertTrue(logs.any { it.contains("$executionContext operation finished: cancelled=false") })
    }

    @Test
    fun `cancellation after checkout rolls back branch and restores stash`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-recover")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
        }
        val runner = runner(
            root,
            git,
            progress = TestOperationProgress {
                if (git.checkoutCount > 0) throw OperationCancelledException()
            },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue(result.cancelled)
        assertTrue(result.recovery?.ok == true)
        assertEquals("main", git.branch)
        assertEquals(1, git.stashApplyCount)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
    }

    @Test
    fun `failed switch with checkpoint is automatically recovered after rollback`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-failed-recover")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                stashApplyCount++
                return GitResult("stash pop", 0, "", "")
            }

            override fun submoduleSync(gitRoot: File): GitResult =
                throw IllegalStateException("sync failed")
        }
        val runner = runner(root, git)

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertFalse(result.ok)
        assertFalse(result.cancelled)
        assertEquals(SwitchExecutionStatus.FAILED, result.execution?.status)
        // The WIP stash is left tracked by the executor (not restored into the dirty
        // new-branch tree), so the automatic recovery can roll back first.
        assertTrue("FAILED-with-checkpoint must trigger automatic recovery", result.recovery?.ok == true)
        assertEquals("main", git.branch)
        assertEquals(1, git.stashApplyCount)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
    }

    @Test
    fun `completed switch retries a lock-blocked stash restore without rollback`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-stash-retry")
        initGitRepo(root.toFile())
        var applyCount = 0
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                applyCount++
                // First apply (end-of-pipeline restore) is blocked by a stale index.lock
                // BEFORE Git starts — the one failure proven safe to retry; the automatic
                // stash-only retry applies it cleanly.
                if (applyCount == 1) {
                    throw IndexLockBlockedException(workDir, File(workDir, ".git/index.lock").path)
                }
                return GitResult("stash apply", 0, "", "")
            }
        }
        val runner = runner(root, git)

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue("a retried stash restore must not fail the switch", result.ok)
        assertEquals("inline restore + automatic retry", 2, applyCount)
        assertTrue(
            "the retry must leave no stash tracked",
            result.execution?.state?.stashesSnapshot().isNullOrEmpty(),
        )
        assertEquals("switch session + retry session", 2, git.openCount)
        assertEquals("retry must not roll back the branch", "dev", git.branch)
    }

    @Test
    fun `timed-out stash apply is never automatically retried`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-stash-timeout")
        initGitRepo(root.toFile())
        var applyCount = 0
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                applyCount++
                return GitResult("stash apply", -1, "", "timeout after 60s")
            }
        }
        val runner = runner(root, git)

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        // A timed-out apply may have PARTIALLY modified the worktree, so it must be marked
        // attempted and never re-applied automatically (at-most-once).
        assertEquals("a timed-out apply must not be retried", 1, applyCount)
        assertTrue("the timed-out apply must not fail the completed switch", result.ok)
        assertTrue(
            "the timed-out WIP must stay tracked and marked attempted",
            result.execution?.state?.stashesSnapshot()?.any { it.restoreAttempted } == true,
        )
    }

    @Test
    fun `user-cancelled stash restore suppresses the automatic retry`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-stash-interrupted")
        initGitRepo(root.toFile())
        var applyCount = 0
        val progress = TestOperationProgress()
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                applyCount++
                // The user cancels while git is applying the stash; the restore must be
                // marked interrupted so the automatic stash-only retry stays suppressed.
                progress.isCanceled = true
                return GitResult("stash apply", -1, "", "cancelled")
            }
        }
        val runner = runner(root, git, progress = progress)

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue("a user-cancelled restore must mark the switch interrupted", result.execution?.stashRestoreInterrupted == true)
        assertEquals("a user-cancelled restore must not auto-retry", 1, applyCount)
        assertFalse(
            "the WIP must stay tracked for a later explicit retry",
            result.execution?.state?.stashesSnapshot().isNullOrEmpty(),
        )
    }

    @Test
    fun `auto recovery runs in its own cancellable background operation`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-recovery-task")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true
            override fun submoduleSync(gitRoot: File): GitResult =
                throw IllegalStateException("sync failed")
        }
        val runTitles = mutableListOf<String>()
        val runner = SwitchRunner(
            projectRoot = root,
            operations = object : GitOperationRunner {
                override suspend fun <T> run(
                    title: String,
                    block: (OperationProgress, GitOperationSession) -> T,
                ): GitOperationResult<T> {
                    runTitles += title
                    return TestGitOperationRunner(git).run(title, block)
                }

                override fun openOperation(): GitOperationSession = git.openOperation()
            },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = Bundle.msg("progress.rollback"),
            stashRestoreTitle = Bundle.msg("progress.stash.restore"),
        )

        // The FAILED switch's automatic recovery now runs as its own task with a
        // visible, cancellable indicator (symmetric with the manual rollback path).
        assertEquals("recovery must run as a second background operation", 2, runTitles.size)
        assertEquals(Bundle.msg("progress.rollback"), runTitles[1])
        assertEquals("main", git.branch)
        assertEquals(1, git.stashApplyCount)
        assertTrue(result.recovery?.ok == true)
    }

    @Test
    fun `operation session creation failure becomes a structured switch failure`() = runBlocking {
        val logs = mutableListOf<String>()
        val unavailableGit = object : GitOperationProvider {
            override fun openOperation(): GitOperationSession =
                throw IllegalStateException("git unavailable")
        }
        val runner = SwitchRunner(
            projectRoot = Files.createTempDirectory("switch-runner-open-failure"),
            operations = TestGitOperationRunner(unavailableGit),
        )

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender(logs::add),
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertFalse(result.ok)
        assertFalse(result.cancelled)
        assertNull(result.execution)
        assertTrue(logs.any { it.contains("git unavailable") })
    }

    @Test
    fun `checkpoint failure becomes a structured switch failure`() = runBlocking {
        val logs = mutableListOf<String>()
        val root = Files.createTempDirectory("switch-runner-execution-failure")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun revParseHead(workDir: File): String? =
                throw IllegalStateException("cannot read HEAD")
        }
        val runner = runner(root, git)

        val result = runner.execute(
            title = "Switching",
            request = request(fetchFirst = false, pull = false),
            log = createStringAppender(logs::add),
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        // A checkpoint query failure must not escape the workflow as an unhandled
        // exception that loses the execution result; it is contained as a structured
        // FAILED result with no checkpoint (nothing mutated yet, so no rollback).
        assertFalse(result.ok)
        assertFalse(result.cancelled)
        assertNotNull(result.execution)
        assertEquals(SwitchExecutionStatus.FAILED, result.execution?.status)
        assertEquals(
            OperationIssueCode.CHECKPOINT_UNAVAILABLE,
            result.execution?.issues?.single()?.code,
        )
        assertTrue(logs.any { it.contains("IllegalStateException: cannot read HEAD") })
    }

    @Test
    fun `cancel recovery session failure is reported without escaping`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-recovery-open-failure")
        initGitRepo(root.toFile())
        val git = object : RecordingGit() {
            override fun isDirty(workDir: File): Boolean = true

            override fun openOperation(): GitOperationSession {
                if (openCount == 1) throw IllegalStateException("recovery unavailable")
                return super.openOperation()
            }
        }
        val runner = runner(
            root,
            git,
            progress = TestOperationProgress {
                if (git.checkoutCount > 0) throw OperationCancelledException()
            },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
            recoveryTitle = "Rolling back",
            stashRestoreTitle = "Restoring stash",
        )

        assertTrue(result.cancelled)
        assertFalse(result.recovery?.ok == true)
        assertEquals(1, git.openCount)
        assertEquals(1, git.closeCount)
    }

    private fun request(
        target: String = "main",
        fetchFirst: Boolean = true,
        pull: Boolean = true,
    ) = ResolvedSwitchRequest.from(
        Preset("dev", target),
        SwitchOptions(fetchFirst = fetchFirst, pull = pull),
    )

    private fun initGitRepo(dir: File) {
        val proc = ProcessBuilder("git", "init", "-b", "main")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        assertTrue("git init should finish", proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("git init should succeed", 0, proc.exitValue())
    }

    private fun runner(
        root: Path,
        git: GitOperationProvider,
        completion: TestOperationCompletion = TestOperationCompletion.COMPLETE,
        progress: TestOperationProgress = TestOperationProgress(),
    ) = SwitchRunner(
        projectRoot = root,
        operations = TestGitOperationRunner(git, completion, progress),
    )

    private open class RecordingGit : GitClient {
        var openCount = 0
        var cancelCount = 0
        var closeCount = 0
        var submoduleSyncCount = 0
        var checkoutCount = 0
        var stashApplyCount = 0
        var branch = "main"

        override fun cancel() { cancelCount++ }
        override fun openOperation(): GitOperationSession {
            openCount++
            val delegate = this
            return object : GitOperationSession, GitWorkflowClient by delegate {
                override fun cancel() = delegate.cancel()

                override fun close() {
                    closeCount++
                }
            }
        }

        override fun currentBranch(workDir: File): String? = branch
        override fun repositoryIdentity(workDir: File): RepositoryIdentity =
            RepositoryIdentity(File(workDir, ".git").absolutePath, null)
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
        override fun resetHard(workDir: File, revision: String): GitResult = ok("reset")
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = ok("stash")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashOidByMessage(workDir: File, messagePrefix: String): String? =
            if (messagePrefix.startsWith("branch-switcher: before -> ")) "stash-oid" else null
        override fun fetch(workDir: File): GitResult = ok("fetch")
        override fun localBranchExists(workDir: File, branch: String): Boolean = true
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        override fun checkoutExisting(workDir: File, branch: String): GitResult {
            checkoutCount++
            this.branch = branch
            return ok("checkout")
        }
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = ok("checkout")
        override fun pullFf(workDir: File, branch: String): GitResult = ok("pull")
        override fun submoduleSync(gitRoot: File): GitResult {
            submoduleSyncCount++
            return ok("submodule sync")
        }
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = ok("submodule init")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main")
        override fun revParseHead(workDir: File): String? = "abc123"
        override fun stashApply(workDir: File, oid: String): GitResult {
            stashApplyCount++
            return ok("stash pop")
        }
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = ok("checkout -b")
        override fun deleteBranch(workDir: File, branch: String): GitResult = ok("branch -d")

        private fun ok(cmd: String) = GitResult(cmd, 0, "", "")
    }
}
