package com.submodule.branchswitcher.log

import com.submodule.branchswitcher.executeTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.StepExecution
import com.submodule.branchswitcher.switch.StepResult
import com.submodule.branchswitcher.switch.SwitchContext
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchState
import com.submodule.branchswitcher.switch.SwitchStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppLoggerTest {
    private val messages = mutableListOf<String>()
    private val projectRoot = Files.createTempDirectory("test-logger")
    private val okGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String) = GitResult("stash", 0, "", "")
        override fun stashPop(workDir: File) = GitResult("pop", 0, "", "")
        override fun fetch(workDir: File) = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch in listOf("main", "dev")
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        override fun checkoutExisting(workDir: File, branch: String) = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String) = GitResult("checkout", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String) = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String) = GitResult("branch", 0, "", "")
        override fun pullFf(workDir: File, branch: String) = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File) = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String) = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev")
        override fun revParseHead(workDir: File): String? = "abc123"
    }

    @Before
    fun setup() {
        messages.clear()
        val process = ProcessBuilder("git", "init")
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start()
        process.inputStream.transferTo(java.io.OutputStream.nullOutputStream())
        process.waitFor()
    }

    @Test
    fun `string appender applies only explicit severity prefixes`() {
        val log = createStringAppender(messages::add)

        log.info("hello")
        log.warn("warning")
        log.error("failure")
        log.debug("detail")
        log.activity("switching")

        assertEquals(
            listOf("hello", "[warn] warning", "[error] failure", "[debug] detail", "switching"),
            messages,
        )
    }

    @Test
    fun `context logger prefixes messages and preserves throwable logging`() {
        val calls = mutableListOf<String>()
        val logger = object : AppLogger {
            override fun info(msg: String) = Unit
            override fun warn(msg: String) = Unit
            override fun error(msg: String) = Unit
            override fun debug(msg: String) = Unit
            override fun activity(msg: String) = Unit
            override fun error(msg: String, error: Throwable) {
                calls += "$msg|${error.javaClass.simpleName}|${error.message}"
            }
        }.withContext("switch-test")

        logger.error("operation failed", IllegalStateException("broken"))

        assertEquals(
            listOf("[switch-test] operation failed|IllegalStateException|broken"),
            calls,
        )
    }

    @Test
    fun `diagnostic fingerprint is stable without exposing its source`() {
        val remote = "https://user:secret@example.test/private/repo.git"

        val first = diagnosticFingerprint(remote)

        assertEquals(first, diagnosticFingerprint(remote))
        assertEquals(12, first.length)
        assertFalse(first.contains("secret"))
        assertEquals("none", diagnosticFingerprint(null))
    }

    @Test
    fun `fatal step is logged as error`() {
        val fatalStep = object : SwitchStep {
            override val name = "always-fatal"
            override fun execute(context: SwitchContext, state: SwitchState) =
                StepExecution(StepResult.Fatal("simulated fatal"), state)
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender(messages::add),
            okGit,
            steps = listOf(fatalStep),
        )

        executor.executeTest(
            Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue(messages.any { it.startsWith("[error]") && it.contains("simulated fatal") })
    }

    @Test
    fun `partial failure is logged as warning rather than error`() {
        val branchMissingGit = object : GitClient by okGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender(messages::add), branchMissingGit)

        executor.executeTest(
            Preset("test", "no-branch"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue(messages.any { it.startsWith("[warn]") && it.contains("no-branch") })
        assertFalse(messages.any { it.startsWith("[error]") && it.contains("no-branch") })
    }
}
