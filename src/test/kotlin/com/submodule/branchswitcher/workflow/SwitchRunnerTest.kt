package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class SwitchRunnerTest {
    @Test
    fun `missing git repo returns structured execution result`() = runBlocking {
        val git = RecordingGit()
        val runner = runner(Files.createTempDirectory("switch-runner"), git)

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
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
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertNull("cancel before run should not create an execution result", result.execution)
    }

    @Test
    fun `execute returns ok when shared runner completes switch pipeline`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-ok")
        initGitRepo(root.toFile())
        val git = RecordingGit()
        val runner = runner(root, git)

        val result = runner.execute(
            title = "Switching",
            request = request(fetchFirst = false, pull = false),
            log = createStringAppender {},
        )

        assertTrue(result.ok)
        assertFalse(result.cancelled)
        assertNotNull(result.execution)
        assertEquals(1, git.submoduleSyncCount)
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
                if (git.checkoutCount > 0) throw CancellationException()
            },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
        )

        assertTrue(result.cancelled)
        assertTrue(result.recovery?.ok == true)
        assertEquals("main", git.branch)
        assertEquals(1, git.stashPopCount)
        assertEquals(2, git.openCount)
        assertEquals(2, git.closeCount)
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
            confirmSubmoduleInitialization = { true },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender(logs::add),
        )

        assertFalse(result.ok)
        assertFalse(result.cancelled)
        assertNull(result.execution)
        assertTrue(logs.any { it.contains("git unavailable") })
    }

    @Test
    fun `execution failure becomes structured result`() = runBlocking {
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
        )

        assertFalse(result.ok)
        assertFalse(result.cancelled)
        assertNull(result.execution)
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
                if (git.checkoutCount > 0) throw CancellationException()
            },
        )

        val result = runner.execute(
            title = "Switching",
            request = request(target = "dev", fetchFirst = false, pull = false),
            log = createStringAppender {},
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
    ) = ResolvedSwitchRequest.resolve(
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
        confirmSubmoduleInitialization = { true },
    )

    private open class RecordingGit : GitClient {
        var openCount = 0
        var cancelCount = 0
        var closeCount = 0
        var submoduleSyncCount = 0
        var checkoutCount = 0
        var stashPopCount = 0
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
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = ok("stash")
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
        override fun stashPop(workDir: File): GitResult {
            stashPopCount++
            return ok("stash pop")
        }
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = ok("checkout -b")
        override fun deleteBranch(workDir: File, branch: String): GitResult = ok("branch -d")

        private fun ok(cmd: String) = GitResult(cmd, 0, "", "")
    }
}
