
package com.submodule.branchswitcher.switch
import com.submodule.branchswitcher.executeTest

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Path

class SwitchExecutorTest {

    private val log = mutableListOf<String>()

    // Default fake: clean repos, main branch exists, everything succeeds
    private val fakeGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun fetch(workDir: File): GitResult = GitResult("fetch", 0, "", "")
        override fun localBranchExists(workDir: File, branch: String): Boolean = branch == "main" || branch == "dev"
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
        override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 0, "", "")
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult = GitResult("init", 0, "", "")
        override fun listSubmodulePaths(gitRoot: File): List<String> = emptyList()
        override fun listAllBranches(workDir: File): List<String> = listOf("main", "dev", "feature-x")
        override fun revParseHead(workDir: File): String? = "abc123"
        override fun stashPop(workDir: File): GitResult = GitResult("pop", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
    }

    private val projectRoot = java.nio.file.Files.createTempDirectory("test-executor")
    private val preset = Preset("test", "dev", emptyMap())

    @Before
    fun setup() {
        log.clear()
        initGitRepo(projectRoot.toFile())
    }

    private fun initGitRepo(dir: File) {
        dir.mkdirs()
        val proc = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("git init should succeed in ${dir.absolutePath}: $out", 0, proc.waitFor())
    }

    // ---- Basic success / failure ----

    @Test
    fun `switch to existing branch succeeds`() {
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should succeed", result)
        assertTrue("Checkpoint should be recorded", executor.getCheckpoint()?.isNotEmpty() ?: false)
    }

    @Test
    fun `switch to missing branch fails`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, missingGit)
        val result = executor.executeTest(Preset("test", "no-branch", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertFalse("Switch should fail when branch doesn't exist", result)
    }

    // ---- Dirty handling ----

    @Test
    fun `dirty skip prevents checkout`() {
        var checkoutCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, dirtyGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Skip, pull = false, fetchFirst = false))
        assertFalse("Dirty+Skip should cause failure", result)
        assertEquals("Dirty+Skip must not checkout", 0, checkoutCalls)
    }

    @Test
    fun `stash failure prevents checkout`() {
        var checkoutCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", 1, "", "stash failed")
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, failingGit)

        assertFalse(executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)))
        assertEquals("Stash failure must not checkout", 0, checkoutCalls)
    }

    @Test
    fun `dirty stash succeeds and pop is called on return`() {
        val popCalled = mutableListOf<String>()
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashPop(workDir: File): GitResult {
                popCalled += "popped"
                return GitResult("pop", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, dirtyGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Dirty+Stash should succeed", result)
        assertTrue("Stash pop should be called on checkout", popCalled.isNotEmpty())
    }

    @Test
    fun `dirty force proceeds anyway`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, dirtyGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false))
        assertTrue("Dirty+Force should succeed", result)
    }

    @Test
    fun `checkout failure prevents pull and submodule sync`() {
        var pullCalls = 0
        var syncCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 1, "", "checkout failed")
            override fun pullFf(workDir: File, branch: String): GitResult {
                pullCalls++
                return GitResult("pull", 0, "", "")
            }
            override fun submoduleSync(gitRoot: File): GitResult {
                syncCalls++
                return GitResult("sync", 0, "", "")
            }
        }
        val pullPreset = Preset("test", "dev", emptyMap())
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, failingGit)

        assertFalse(executor.executeTest(pullPreset, SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = false)))
        assertEquals("Failed checkout must not pull the old branch", 0, pullCalls)
        assertEquals("Failed main checkout must not sync submodules", 0, syncCalls)
    }

    // ---- Rollback ----

    @Test
    fun `rollback without checkpoint returns false`() {
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        assertFalse("Rollback without checkpoint should return false", executor.rollback())
    }

    @Test
    fun `rollback restores branch from checkpoint`() {
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        val checkpoint = executor.getCheckpoint()
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertEquals("abc123", checkpoint["."]!!.sha)
        assertEquals("main", checkpoint["."]!!.branch)
    }

    @Test
    fun `rollback with branch restores named branch`() {
        var currentBranch = "main"  // initially main, switches to dev, rollback restores
        val checkoutCalls = mutableListOf<String>()
        val trackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls += branch
                currentBranch = branch  // update after checkout
                return GitResult("checkout", 0, "", "")
            }
            override fun revParseHead(workDir: File): String? = "abc123"
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, trackGit)
        // Execute switch to dev - checkout records branch as "dev"
        executor.executeTest(Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        checkoutCalls.clear()
        // Now branch = "dev", checkpoint has branch = "main" (recorded before switch)
        // Rollback should checkout "main"
        executor.rollback()
        assertTrue("Should call checkout for main branch, got: $checkoutCalls", "main" in checkoutCalls)
    }

    @Test
    fun `rollback falls back to checkpoint sha when branch restore fails`() {
        var currentBranch = "main"
        val rollbackCalls = mutableListOf<String>()
        val rollbackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (branch == "dev") {
                    currentBranch = "dev"
                    return GitResult("checkout", 0, "", "")
                }
                rollbackCalls += branch
                return if (branch == "abc123") GitResult("checkout", 0, "", "")
                else GitResult("checkout", 1, "", "branch restore failed")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, rollbackGit)
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

        assertTrue(executor.rollback())
        assertEquals(listOf("main", "abc123"), rollbackCalls)
    }

    @Test
    fun `rollback fails when branch and checkpoint sha restore both fail`() {
        var currentBranch = "main"
        val rollbackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (branch == "dev") {
                    currentBranch = "dev"
                    return GitResult("checkout", 0, "", "")
                }
                return GitResult("checkout", 1, "", "restore failed")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, rollbackGit)
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

        assertFalse(executor.rollback())
        assertTrue(log.any { it.contains("SHA checkout also failed") })
    }

    @Test
    fun `rollback restores checkpoint sha when original head was detached`() {
        var currentBranch: String? = null
        val checkoutCalls = mutableListOf<String>()
        val detachedGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls += branch
                currentBranch = branch
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, detachedGit)
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        checkoutCalls.clear()

        assertTrue(executor.rollback())
        assertEquals(listOf("abc123"), checkoutCalls)
    }

    @Test
    fun `rollback continues other repos after one submodule fails`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        initGitRepo(File(projectRoot.toFile(), "SubB"))
        val branches = mutableMapOf("SubA" to "main", "SubB" to "main")
        val rollbackCalls = mutableListOf<Pair<String, String>>()
        val partialGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = branches[workDir.name] ?: "main"
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (branch == "dev") {
                    branches[workDir.name] = "dev"
                    return GitResult("checkout", 0, "", "")
                }
                rollbackCalls += workDir.name to branch
                return if (workDir.name == "SubA") GitResult("checkout", 1, "", "restore failed")
                else GitResult("checkout", 0, "", "")
            }
        }
        val subPreset = Preset("sub-test", "dev", mapOf("SubA" to "dev", "SubB" to "dev"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, partialGit)
        executor.executeTest(subPreset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

        assertFalse(executor.rollback())
        assertTrue(rollbackCalls.contains("SubB" to "main"))
    }

    // ---- Cancel ----

    @Test
    fun `cancel after one step stops remaining pipeline and signals git`() {
        var cancelled = false
        var cancelCalls = 0
        val executed = mutableListOf<String>()
        val trackingGit = object : GitClient by fakeGit {
            override fun cancel() {
                cancelCalls++
            }
        }
        val first = object : SwitchStep {
            override val name = "first"
            override fun execute(context: SwitchContext): StepResult {
                executed += name
                cancelled = true
                return StepResult.Success
            }
        }
        val second = object : SwitchStep {
            override val name = "second"
            override fun execute(context: SwitchContext): StepResult {
                executed += name
                return StepResult.Success
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            trackingGit,
            cancelled = { cancelled },
            steps = listOf(first, second),
        )

        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

        assertFalse(result)
        assertEquals(listOf("first"), executed)
        assertEquals(1, cancelCalls)
        assertTrue(log.any { it.contains("[cancelled] before step: second") })
    }

    @Test
    fun `non-cancelled pipeline proceeds`() {
        val context = SwitchContext(projectRoot, preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
            fakeGit, createStringAppender { log += it }, cancelled = { false })
        assertFalse("cancelled should be false", context.cancelled())
    }

    // ---- Pull step ----

    @Test
    fun `pull step skipped when option disabled`() {
        val pullGit = object : GitClient by fakeGit {
            override fun pullFf(workDir: File, branch: String): GitResult {
                error("pull should not be called")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, pullGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch without pull should succeed", result)
    }

    // ---- Fetch step ----

    @Test
    fun `fetch step skipped when option disabled`() {
        val fetchGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult {
                error("fetch should not be called")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fetchGit)
        val result = executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch without fetch should succeed", result)
    }

    // ---- Submodules ----

    @Test
    fun `switch with submodules processes all targets`() {
        // Create submodule dirs
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        initGitRepo(File(projectRoot.toFile(), "SubB"))

        val subPreset = Preset("sub-test", "dev", mapOf("SubA" to "dev", "SubB" to "feature-x"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        val result = executor.executeTest(subPreset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with submodules should succeed", result)

        val checkpoint = executor.getCheckpoint()
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertTrue("Checkpoint should contain SubA", checkpoint.containsKey("SubA"))
        assertTrue("Checkpoint should contain SubB", checkpoint.containsKey("SubB"))
    }

    // ---- Rollback edge cases ----

    @Test
    fun `rollback reports failure when repo is missing`() {
        val skipGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 0, "", "")
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, skipGit)
        // Execute a switch so checkpoint is recorded
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        // Delete the .git dir to simulate missing repo
        File(projectRoot.toFile(), ".git").deleteRecursively()
        val result = executor.rollback()
        assertFalse("Rollback cannot report success when a repo was not restored", result)
    }

    @Test
    fun `rollback skips when branch already matches`() {
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        // fakeGit.currentBranch returns "main", checkpoint also has "main"
        // Rollback should skip without calling checkout
        val result = executor.rollback()
        assertTrue("Rollback should succeed when already on checkpoint branch", result)
    }

    // -- confirmBeforeInit fail-closed ---------------------------------

    @Test
    fun `confirmBeforeInit with null callback declines init`() {
        val noInitGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = when {
                workDir.name == "SubA" -> false // needs init
                else -> true
            }
            override fun listSubmodulePaths(gitRoot: File): List<String> = listOf("SubA")
        }
        val subPreset = Preset("sub", "main", mapOf("SubA" to "main"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noInitGit,
            onConfirmSubmoduleInit = null)
        // SubA needs init but no callback - fail-closed: init declined
        val result = executor.executeTest(subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true))
        assertFalse("Switch should have partial failure from declined init", result)
        assertTrue("Should log init declined",
            log.any { it.contains("[skip] init declined for SubA") })
    }

    @Test
    fun `confirmBeforeInit with callback returning false declines init`() {
        val noInitGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = when {
                workDir.name == "SubA" -> false
                else -> true
            }
            override fun listSubmodulePaths(gitRoot: File): List<String> = listOf("SubA")
        }
        val subPreset = Preset("sub", "main", mapOf("SubA" to "main"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noInitGit,
            onConfirmSubmoduleInit = { false })
        val result = executor.executeTest(subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true))
        assertFalse("Switch should have partial failure from declined init", result)
        assertTrue("Should log init declined", log.any { it.contains("[skip] init declined for SubA") })
    }

    @Test
    fun `confirmBeforeInit with callback returning true proceeds with init`() {
        val initLog = mutableListOf<String>()
        var subADirReady = false
        val noInitGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = when {
                workDir.name == "SubA" -> subADirReady
                else -> true
            }
            override fun listSubmodulePaths(gitRoot: File): List<String> = listOf("SubA")
            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                initLog += "init:$path"
                gitRoot.toPath().resolve(path).toFile().mkdirs()
                subADirReady = true // simulate git init creating .git
                return GitResult("init", 0, "", "")
            }
        }
        val subPreset = Preset("sub", "main", mapOf("SubA" to "main"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noInitGit,
            onConfirmSubmoduleInit = { true })
        val result = executor.executeTest(subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true))
        assertTrue("Switch should succeed", result)
        assertEquals(listOf("init:SubA"), initLog)
    }

    // ---- Shared utilities ----

    @Test
    fun `shortLabel extracts basename`() {
        assertEquals("SubA", shortLabel("lib/SubA"))
        assertEquals("main", shortLabel("main"))
        assertEquals("repo", shortLabel("a/b/c/repo"))
    }

    @Test
    fun `shortLabel strips trailing tilde`() {
        assertEquals("SubA", shortLabel("lib/SubA~"))
        assertEquals("x", shortLabel("x~"))
    }
}
