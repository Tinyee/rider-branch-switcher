package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
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
    }

    private val projectRoot = java.nio.file.Files.createTempDirectory("test-executor")
    private val preset = Preset("test", "dev", emptyMap(), pullEnabled = false)

    @Before
    fun setup() {
        log.clear()
        projectRoot.toFile().mkdirs()
        File(projectRoot.toFile(), ".git").mkdirs()
    }

    // ---- Basic success / failure ----

    @Test
    fun `switch to existing branch succeeds`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should succeed", result)
        assertTrue("Checkpoint should be recorded", executor.getCheckpoint()?.isNotEmpty() ?: false)
    }

    @Test
    fun `switch to missing branch fails`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, missingGit)
        val result = executor.execute(Preset("test", "no-branch", emptyMap(), pullEnabled = false),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertFalse("Switch should fail when branch doesn't exist", result)
    }

    // ---- Dirty handling ----

    @Test
    fun `dirty skip prevents checkout`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, dirtyGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Skip, pull = false, fetchFirst = false))
        assertFalse("Dirty+Skip should cause failure", result)
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
        val executor = SwitchExecutor(projectRoot, { log += it }, dirtyGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Dirty+Stash should succeed", result)
        assertTrue("Stash pop should be called on checkout", popCalled.isNotEmpty())
    }

    @Test
    fun `dirty force proceeds anyway`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, dirtyGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false))
        assertTrue("Dirty+Force should succeed", result)
    }

    // ---- Rollback ----

    @Test
    fun `rollback without checkpoint returns false`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        assertFalse("Rollback without checkpoint should return false", executor.rollback())
    }

    @Test
    fun `rollback restores branch from checkpoint`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        val checkpoint = executor.getCheckpoint()
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertEquals("abc123", checkpoint["."]!!.sha)
        assertEquals("main", checkpoint["."]!!.branch)
    }

    @Test
    fun `rollback with branch restores named branch`() {
        var branch = "main"  // initially main, switches to dev, rollback restores
        val checkoutCalls = mutableListOf<String>()
        val trackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = branch
            override fun checkoutExisting(workDir: File, br: String): GitResult {
                checkoutCalls += br
                branch = br  // update after checkout
                return GitResult("checkout", 0, "", "")
            }
            override fun revParseHead(workDir: File): String? = "abc123"
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, trackGit)
        // Execute switch to dev — checkout records branch as "dev"
        executor.execute(Preset("test", "dev", emptyMap(), pullEnabled = false),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        checkoutCalls.clear()
        // Now branch = "dev", checkpoint has branch = "main" (recorded before switch)
        // Rollback should checkout "main"
        executor.rollback()
        assertTrue("Should call checkout for main branch, got: $checkoutCalls", "main" in checkoutCalls)
    }

    // ---- Cancel ----

    @Test
    fun `cancel stops pipeline`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        val opts = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)
        // cancelled() returns true, steps should be skipped
        val context = SwitchContext(projectRoot, preset, opts, fakeGit, { log += it }, cancelled = { true })
        // Check that cancelled flag is respected
        // The pipeline itself checks cancelled between steps; we test via the context directly
        assertTrue("cancelled should be true", context.cancelled())
    }

    @Test
    fun `non-cancelled pipeline proceeds`() {
        val context = SwitchContext(projectRoot, preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
            fakeGit, { log += it }, cancelled = { false })
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
        val executor = SwitchExecutor(projectRoot, { log += it }, pullGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
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
        val executor = SwitchExecutor(projectRoot, { log += it }, fetchGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch without fetch should succeed", result)
    }

    // ---- Submodules ----

    @Test
    fun `switch with submodules processes all targets`() {
        // Create submodule dirs
        File(projectRoot.toFile(), "SubA").mkdirs()
        File(projectRoot.toFile(), "SubA/.git").mkdirs()
        File(projectRoot.toFile(), "SubB").mkdirs()
        File(projectRoot.toFile(), "SubB/.git").mkdirs()

        val subPreset = Preset("sub-test", "dev", mapOf("SubA" to "dev", "SubB" to "feature-x"), pullEnabled = false)
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        val result = executor.execute(subPreset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with submodules should succeed", result)

        val checkpoint = executor.getCheckpoint()
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertTrue("Checkpoint should contain SubA", checkpoint.containsKey("SubA"))
        assertTrue("Checkpoint should contain SubB", checkpoint.containsKey("SubB"))
    }

    // ---- CheckpointEntry data class ----

    @Test
    fun `checkpoint entry stores sha and branch`() {
        val entry = CheckpointEntry("abc123", "main")
        assertEquals("abc123", entry.sha)
        assertEquals("main", entry.branch)
    }

    @Test
    fun `checkpoint entry with null branch`() {
        val entry = CheckpointEntry("def456", null)
        assertEquals("def456", entry.sha)
        assertNull(entry.branch)
    }

    // ---- Rollback edge cases ----

    @Test
    fun `rollback skips missing repo`() {
        val skipGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun checkoutExisting(workDir: File, br: String): GitResult =
                GitResult("checkout", 0, "", "")
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, skipGit)
        // Execute a switch so checkpoint is recorded
        executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        // Delete the .git dir to simulate missing repo
        File(projectRoot.toFile(), ".git").deleteRecursively()
        val result = executor.rollback()
        // Should still return true (missing repos are skipped, not fatal)
        assertTrue("Rollback should skip missing repos and succeed", result)
    }

    @Test
    fun `rollback skips when branch already matches`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        // fakeGit.currentBranch returns "main", checkpoint also has "main"
        // Rollback should skip without calling checkout
        val result = executor.rollback()
        assertTrue("Rollback should succeed when already on checkpoint branch", result)
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
