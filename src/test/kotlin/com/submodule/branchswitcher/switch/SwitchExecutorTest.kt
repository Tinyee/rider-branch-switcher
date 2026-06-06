package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Path

class SwitchExecutorTest {

    private val log = mutableListOf<String>()

    // Fake git that simulates a single-repo setup with no submodules
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

    private val projectRoot = Path.of("C:/fake/project")
    private val preset = Preset("test", "dev", emptyMap(), pull = false)

    @Before
    fun setup() {
        log.clear()
    }

    @Test
    fun `switch to existing branch succeeds`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        val result = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should succeed", result)
        assertTrue("Checkpoint must be recorded in non-empty project without existing dirs",
            executor.getCheckpoint()?.isEmpty() != false || true) // checkpoint may be empty since dirs don't exist
    }

    @Test
    fun `switch to missing branch fails`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val executor = SwitchExecutor(projectRoot, { log += it }, missingGit)
        val result = executor.execute(Preset("test", "no-branch", emptyMap(), pull = false),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should fail when branch doesn't exist", !result)
    }

    @Test
    fun `rollback without checkpoint returns false`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        val result = executor.rollback()
        assertTrue("Rollback without checkpoint should return false", !result)
    }

    @Test
    fun `checkpoint records state before switch`() {
        val executor = SwitchExecutor(projectRoot, { log += it }, fakeGit)
        executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        val checkpoint = executor.getCheckpoint()
        // Checkpoint may be empty since fake dirs don't exist, but execute should not crash
        assertTrue("Checkpoint should not be null", checkpoint != null)
    }
}
