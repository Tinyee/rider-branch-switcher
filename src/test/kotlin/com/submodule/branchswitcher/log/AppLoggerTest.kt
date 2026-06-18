
package com.submodule.branchswitcher.log
import com.submodule.branchswitcher.executeTest

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.StepResult
import com.submodule.branchswitcher.switch.SwitchContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests the structured logging contract:
 * - createStringAppender prefix formats
 * - Step failure -> WARN (business failures)
 * - StepResult.Fatal -> ERROR
 * - StepResult.Partial -> WARN
 * - Switch start/end + rollback -> ACTIVITY
 * - Normal progress -> INFO or DEBUG
 *
 * Step-level tests are exercised via [SwitchExecutor] to avoid coupling to
 * the temp-directory setup required by direct [SwitchStep.execute] calls.
 */
class AppLoggerTest {

    private val log = mutableListOf<String>()
    private val projectRoot: Path = Files.createTempDirectory("test-logger")

    private val okGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashPop(workDir: File): GitResult = GitResult("pop", 0, "", "")
        override fun fetch(workDir: File): GitResult = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch in listOf("main", "dev")
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
        override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev")
        override fun revParseHead(workDir: File): String? = "abc123"
    }

    private fun appender() = createStringAppender { log += it }

    @Before
    fun setup() {
        log.clear()
        val dir = projectRoot.toFile()
        dir.mkdirs()
        val proc = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.transferTo(java.io.OutputStream.nullOutputStream())
        val exit = proc.waitFor()
        assertTrue("git init must succeed in ${dir.absolutePath}", exit == 0)
    }

    // -- createStringAppender prefix contracts -----------------------

    @Test fun `info passes message unchanged`() {
        appender().info("hello")
        assertEquals("hello", log.single())
    }

    @Test fun `warn wraps with warn prefix`() {
        appender().warn("something wrong")
        assertEquals("[warn] something wrong", log.single())
    }

    @Test fun `error wraps with error prefix`() {
        appender().error("fatal issue")
        assertEquals("[error] fatal issue", log.single())
    }

    @Test fun `debug wraps with debug prefix`() {
        appender().debug("diagnostic")
        assertEquals("[debug] diagnostic", log.single())
    }

    @Test fun `activity passes message unchanged`() {
        appender().activity("=== switching ===")
        assertEquals("=== switching ===", log.single())
    }

    @Test
    fun `git repo is created in setup`() {
        val dir = projectRoot.toFile()
        val dotGit = File(dir, ".git")
        assertTrue("projectRoot should exist: $dir", dir.exists())
        assertTrue(".git should exist: $dotGit", dotGit.exists())
        assertTrue(".git should be directory: $dotGit", dotGit.isDirectory)
        assertTrue("isGitRepo should return true", GitOps(timeoutSeconds = 10).isGitRepo(dir))
    }

    // -- Log levels via SwitchExecutor ---------------------------------

    @Test
    fun `fatal step produces error`() {
        val logCollector = mutableListOf<String>()
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            okGit,
            steps = listOf(object : com.submodule.branchswitcher.switch.SwitchStep {
                override val name = "always-fatal"
                override fun execute(context: SwitchContext): StepResult =
                    StepResult.Fatal("simulated fatal")
            })
        )
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Fatal should be ERROR level, got: $logCollector",
            logCollector.any { it.startsWith("[error]") && it.contains("simulated fatal") })
    }

    @Test
    fun `partial failure produces warn not error`() {
        val logCollector = mutableListOf<String>()
        val branchMissingGit = object : GitClient by okGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            branchMissingGit)
        executor.executeTest(Preset("test", "no-branch"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Partial failure should be WARN, got: $logCollector",
            logCollector.any { it.startsWith("[warn]") && it.contains("no-branch") })
        assertFalse("Partial failure should NOT be ERROR",
            logCollector.any { it.startsWith("[error]") && it.contains("no-branch") })
    }

    @Test
    fun `fetch failure produces warn`() {
        val logCollector = mutableListOf<String>()
        val fetchFailGit = object : GitClient by okGit {
            override fun fetch(workDir: File): GitResult = GitResult("fetch", 1, "", "network error")
        }
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            fetchFailGit,
            steps = listOf(
                com.submodule.branchswitcher.switch.DirtyHandlingStep(),
                com.submodule.branchswitcher.switch.FetchStep(),
            )
        )
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true))
        assertTrue("Fetch failure should be WARN, got: $logCollector",
            logCollector.any { it.startsWith("[warn]") && it.contains("fetch warn") })
    }

    @Test
    fun `stash failure produces warn`() {
        val logCollector = mutableListOf<String>()
        val stashFailGit = object : GitClient by okGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 1, "", "lock fail")
        }
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            stashFailGit,
            steps = listOf(com.submodule.branchswitcher.switch.DirtyHandlingStep())
        )
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Stash failure should be WARN, got: $logCollector",
            logCollector.any { it.startsWith("[warn]") && it.contains("FAIL") })
    }

    @Test
    fun `pull failure produces warn`() {
        val logCollector = mutableListOf<String>()
        val pullFailGit = object : GitClient by okGit {
            override fun currentBranch(workDir: File): String? = "dev" // checkout succeeded
            override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 1, "", "conflict")
        }
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            pullFailGit,
            steps = listOf(
                com.submodule.branchswitcher.switch.DirtyHandlingStep(),
                com.submodule.branchswitcher.switch.CheckoutStep(),
                com.submodule.branchswitcher.switch.PullStep(),
            )
        )
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = false))
        assertTrue("Pull failure should be WARN, got: $logCollector",
            logCollector.any { it.startsWith("[warn]") && it.contains("pull failed") })
    }

    @Test
    fun `switch start and done use activity`() {
        val logCollector = mutableListOf<String>()
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            okGit)
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Start should be ACTIVITY (bare)",
            logCollector.any { it.startsWith("=== switching to preset:") })
        assertTrue("Done should be ACTIVITY (bare)",
            logCollector.any { it.startsWith("=== done") })
    }

    @Test
    fun `rollback start and done use activity`() {
        val logCollector = mutableListOf<String>()
        val executor = SwitchExecutor(projectRoot,
            createStringAppender { logCollector += it },
            okGit)
        executor.executeTest(Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        logCollector.clear()
        executor.rollback()
        assertTrue("Rollback start should be ACTIVITY (bare), got: $logCollector",
            logCollector.any { it.startsWith("=== rolling back to pre-switch state ===") })
    }
}
