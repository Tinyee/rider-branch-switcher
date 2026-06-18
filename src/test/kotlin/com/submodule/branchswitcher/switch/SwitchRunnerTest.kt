package com.submodule.branchswitcher.switch

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class SwitchRunnerTest {

    private val project: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> null } as Project

    private val indicator: ProgressIndicator = Proxy.newProxyInstance(
        ProgressIndicator::class.java.classLoader,
        arrayOf(ProgressIndicator::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Double.TYPE -> 0.0
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as ProgressIndicator

    @Test
    fun `execute pairs begin and end operation`() = runBlocking {
        val git = RecordingGit()
        val taskRunner = immediateTaskRunner()
        val runner = SwitchRunner(project, Files.createTempDirectory("switch-runner").also {
            it.toFile().deleteOnExit()
        }, git, taskRunner)

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
        )

        assertFalse("missing git repo should fail through executor", result.ok)
        assertFalse(result.cancelled)
        assertNotNull("executor should be available for rollback decisions", result.executor)
        assertEquals(1, git.beginCount)
        assertEquals(1, git.endCount)
        assertEquals(0, git.cancelCount)
    }

    @Test
    fun `task cancellation invokes git cancel and returns cancelled result`() = runBlocking {
        val git = RecordingGit()
        val runner = SwitchRunner(project, Files.createTempDirectory("switch-runner-cancel"), git, cancellingTaskRunner())

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertNull("cancel before run should not create executor", result.executor)
        assertEquals(1, git.beginCount)
        assertEquals(1, git.cancelCount)
        assertEquals(1, git.endCount)
    }

    @Test
    fun `beforeExecute false cancels without creating executor`() = runBlocking {
        val git = RecordingGit()
        val runner = SwitchRunner(project, Files.createTempDirectory("switch-runner-before"), git, immediateTaskRunner())

        val result = runner.execute(
            title = "Switching",
            request = request(),
            log = createStringAppender {},
            beforeExecute = { false },
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertNull(result.executor)
        assertEquals(1, git.beginCount)
        assertEquals(1, git.endCount)
        assertEquals(0, git.cancelCount)
    }

    @Test
    fun `execute returns ok when shared runner completes switch pipeline`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-ok")
        initGitRepo(root.toFile())
        val git = RecordingGit()
        val runner = SwitchRunner(project, root, git, immediateTaskRunner())

        val result = runner.execute(
            title = "Switching",
            request = request(fetchFirst = false, pull = false),
            log = createStringAppender {},
        )

        assertTrue(result.ok)
        assertFalse(result.cancelled)
        assertNotNull(result.executor)
        assertEquals(1, git.beginCount)
        assertEquals(1, git.endCount)
        assertEquals(1, git.submoduleSyncCount)
    }

    @Test
    fun `beforeExecute true runs before switch executor and allows execution`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-before-ok")
        initGitRepo(root.toFile())
        val git = RecordingGit()
        val called = AtomicBoolean(false)
        val runner = SwitchRunner(project, root, git, immediateTaskRunner())

        val result = runner.execute(
            title = "Switching",
            request = request(fetchFirst = false, pull = false),
            log = createStringAppender {},
            beforeExecute = {
                called.set(true)
                true
            },
        )

        assertTrue(called.get())
        assertTrue(result.ok)
        assertEquals(1, git.submoduleSyncCount)
    }

    @Test
    fun `beforeExecute throwing ProcessCanceledException returns cancelled result`() = runBlocking {
        val root = Files.createTempDirectory("switch-runner-pce")
        initGitRepo(root.toFile())
        val git = RecordingGit()
        val runner = SwitchRunner(project, root, git, immediateTaskRunner())

        val result = runner.execute(
            title = "Switching",
            request = request(fetchFirst = false, pull = false),
            log = createStringAppender {},
            beforeExecute = { throw com.intellij.openapi.progress.ProcessCanceledException() },
        )

        assertTrue("should be cancelled", result.cancelled)
        assertFalse("should not be ok", result.ok)
        assertNull("executor should not be created", result.executor)
        assertEquals("onCancel must fire gitClient.cancel()", 1, git.cancelCount)
        assertEquals(1, git.beginCount)
        assertEquals(1, git.endCount)
    }

    private fun request(fetchFirst: Boolean = true, pull: Boolean = true) = ResolvedSwitchRequest.resolve(
        Preset("dev", "main"),
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

    private fun immediateTaskRunner(): TaskBridge.TaskRunner =
        object : TaskBridge.TaskRunner {
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

    private fun cancellingTaskRunner(): TaskBridge.TaskRunner =
        object : TaskBridge.TaskRunner {
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

    private class RecordingGit : GitClient {
        var beginCount = 0
        var cancelCount = 0
        var endCount = 0
        var submoduleSyncCount = 0

        override fun beginOperation() { beginCount++ }
        override fun cancel() { cancelCount++ }
        override fun endOperation() { endCount++ }

        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = ok("stash")
        override fun fetch(workDir: File): GitResult = ok("fetch")
        override fun localBranchExists(workDir: File, branch: String): Boolean = true
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        override fun checkoutExisting(workDir: File, branch: String): GitResult = ok("checkout")
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
        override fun stashPop(workDir: File): GitResult = ok("stash pop")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = ok("checkout -b")
        override fun deleteBranch(workDir: File, branch: String): GitResult = ok("branch -d")

        private fun ok(cmd: String) = GitResult(cmd, 0, "", "")
    }
}
