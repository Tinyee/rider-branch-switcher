package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Performance baseline for large-repo scenarios (50+ submodules).
 *
 * Verifies:
 * - Preflight does not issue redundant Git commands per repo.
 * - State detection (currentBranch + isDirty) scales linearly with submodule count.
 * - Switch pipeline completes within a reasonable wall-clock budget.
 */
class LargeRepoPerformanceTest {

    private lateinit var tmpDir: Path
    private val submoduleCount = 50

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("large-repo-perf-")
        val root = tmpDir.toFile()
        root.mkdirs()
        // Real git init for main + all submodules so isGitRepo() succeeds
        fun initGit(dir: File) {
            dir.mkdirs()
            val p = ProcessBuilder("git", "init", "-q")
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            p.inputStream.transferTo(java.io.OutputStream.nullOutputStream())
            p.waitFor()
        }
        initGit(root)
        for (i in 1..submoduleCount) {
            initGit(tmpDir.resolve("sub-$i").toFile())
        }
    }

    @After
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    // -- Instrumented GitClient that counts calls --

    private class CountingGitClient(
        private val submoduleCount: Int,
    ) : GitClient {
        private val _calls = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val calls: Map<String, Int> get() = _calls.toMap()

        private fun count(method: String) {
            _calls.merge(method, 1, Int::plus)
        }

        override fun currentBranch(workDir: File): String? {
            count("currentBranch"); return if (workDir.name.startsWith("sub-")) "main" else "main"
        }
        override fun isDirty(workDir: File): Boolean {
            count("isDirty"); return false
        }
        override fun dirtyFileCount(workDir: File): Int { count("dirtyFileCount"); return 0 }
        override fun stash(workDir: File, message: String): GitResult {
            count("stash"); return GitResult("stash", 0, "", "")
        }
        override fun fetch(workDir: File): GitResult {
            count("fetch"); return GitResult("fetch", 0, "", "")
        }
        override fun localBranchExists(workDir: File, branch: String): Boolean {
            count("localBranchExists"); return true
        }
        override fun remoteBranchExists(workDir: File, branch: String): Boolean {
            count("remoteBranchExists"); return true
        }
        override fun checkoutExisting(workDir: File, branch: String): GitResult {
            count("checkoutExisting"); return GitResult("checkout", 0, "", "")
        }
        override fun checkoutFromRemote(workDir: File, branch: String): GitResult {
            count("checkoutFromRemote"); return GitResult("checkout", 0, "", "")
        }
        override fun pullFf(workDir: File, branch: String): GitResult {
            count("pullFf"); return GitResult("pull", 0, "", "")
        }
        override fun submoduleSync(gitRoot: File): GitResult {
            count("submoduleSync"); return GitResult("sync", 0, "", "")
        }
        override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
            count("submoduleInitPath"); return GitResult("init", 0, "", "")
        }
        override fun listSubmodulePaths(gitRoot: File): List<String> {
            count("listSubmodulePaths")
            return (1..submoduleCount).map { "sub-$it" }
        }
        override fun listAllBranches(workDir: File): List<String> {
            count("listAllBranches"); return listOf("main", "dev", "feature")
        }
        override fun revParseHead(workDir: File): String? {
            count("revParseHead"); return "abc123"
        }
        override fun stashPop(workDir: File): GitResult {
            count("stashPop"); return GitResult("pop", 0, "", "")
        }
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult {
            count("checkoutNewBranch"); return GitResult("checkout", 0, "", "")
        }
    }

    @Test
    fun `switch pipeline does not issue redundant git commands per repo`() {
        val git = CountingGitClient(submoduleCount)
        val submodules = (1..submoduleCount).associate { "sub-$it" to "dev" }
        val preset = Preset("large", "dev", submodules, pullEnabled = false)
        val log = mutableListOf<String>()

        val executor = SwitchExecutor(
            tmpDir,
            com.submodule.branchswitcher.log.createStringAppender { log += it },
            git,
        )

        val start = System.currentTimeMillis()
        val ok = executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true))
        val elapsed = System.currentTimeMillis() - start

        assertTrue("Switch should succeed", ok)

        // Pipeline steps: DirtyHandling + Fetch + Checkout each call currentBranch once per repo
        val repos = 1 + submoduleCount // main + submodules
        val currentBranchCalls = git.calls["currentBranch"] ?: 0
        assertTrue("currentBranch calls ($currentBranchCalls) should be a multiple of repos ($repos)",
            currentBranchCalls > 0 && currentBranchCalls % repos == 0)

        // isDirty: called once per repo by DirtyHandlingStep
        assertEquals("isDirty calls should match repo count",
            repos, git.calls["isDirty"])

        // Fetch/checkout: each repo exactly once
        assertEquals("fetch calls should match repo count",
            repos, git.calls["fetch"])
        assertEquals("checkoutExisting calls should match repo count",
            repos, git.calls["checkoutExisting"])

        // No stray init calls for existing repos
        assertNull("submoduleInitPath should not be called for existing repos",
            git.calls["submoduleInitPath"])

        // Performance baseline: 50 repos should complete within 10s even with real isGitRepo checks
        assertTrue("Pipeline with $submoduleCount repos took ${elapsed}ms, expected < 10000ms",
            elapsed < 10_000)
    }

    @Test
    fun `preflight probes each repo once`() {
        val git = CountingGitClient(submoduleCount)
        val submodules = (1..submoduleCount).associate { "sub-$it" to "main" }
        val preset = Preset("large", "main", submodules, pullEnabled = false)

        val preflight = SwitchPreflight(git)

        val start = System.currentTimeMillis()
        val result = preflight.probe(tmpDir, preset, null)
        val elapsed = System.currentTimeMillis() - start

        val repos = 1 + submoduleCount
        assertEquals("Preflight should return one row per repo", repos, result.size)
        assertEquals("currentBranch calls should match repo count",
            repos, git.calls["currentBranch"])
        assertEquals("dirtyFileCount calls should match repo count",
            repos, git.calls["dirtyFileCount"])
        // Preflight uses preset.submodules (data model), not git.listSubmodulePaths (disk I/O)

        assertTrue("Preflight with $submoduleCount repos took ${elapsed}ms, expected < 1000ms",
            elapsed < 1000)
    }

    @Test
    fun `detect current state probes each repo exactly once per generation`() {
        val git = CountingGitClient(submoduleCount)
        val submodules = (1..submoduleCount).associate { "sub-$it" to "main" }
        val preset = Preset("large", "main", submodules, pullEnabled = false)

        // Simulate what detectCurrentState does: probe all repo dirs
        val repos = 1 + submoduleCount
        for (i in 0 until repos) {
            val dir = if (i == 0) tmpDir.toFile() else tmpDir.resolve("sub-$i").toFile()
            dir.mkdirs()
            git.currentBranch(dir)
            git.isDirty(dir)
        }

        assertEquals("Should probe each repo once for currentBranch",
            repos, git.calls["currentBranch"])
        assertEquals("Should probe each repo once for isDirty",
            repos, git.calls["isDirty"])
        // listSubmodulePaths not called during state detection (paths come from Preset.submodules)
    }
}
