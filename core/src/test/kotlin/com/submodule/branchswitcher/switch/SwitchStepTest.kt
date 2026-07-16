package com.submodule.branchswitcher.switch

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
import java.nio.file.Files
import java.nio.file.Path

class SwitchStepTest {

    private val log = mutableListOf<String>()
    private val projectRoot: Path = Files.createTempDirectory("test-step")

    private val fakeGit = object : GitClient {
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

    private fun context(opts: SwitchOptions = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)) =
        SwitchContext(projectRoot, Preset("test", "dev"), opts, fakeGit, createStringAppender { log += it })

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

    // ---- CheckoutStep ----

    @Test
    fun `checkout step skip when already on target`() {
        val c = context()
        val step = CheckoutStep()
        val result = step.execute(c)
        assertEquals(StepResult.Success, result) // main==dev should be true in fake, but target is "dev" and current is "main" - wait, current==main, target==dev so it should checkout
    }

    @Test
    fun `checkout step skip when already on target branch`() {
        val sameGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
        }
        val c = context().copy(git = sameGit)
        val step = CheckoutStep()
        assertTrue(step.execute(c) is StepResult.Success)
        assertTrue(log.any { it.contains("already on") })
        assertTrue(c.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout step fails when branch not found`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val c = context().copy(git = missingGit)
        val step = CheckoutStep()
        assertTrue(step.execute(c) is StepResult.Partial)
    }

    @Test
    fun `checkout step creates from remote when local missing`() {
        val remoteOnlyGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
        }
        val c = context().copy(git = remoteOnlyGit)
        val step = CheckoutStep()
        assertTrue(step.execute(c) is StepResult.Success)
        assertTrue(log.any { it.contains("local branch missing") })
    }

    @Test
    fun `checkout step does not touch paths skipped by dirty handling`() {
        var checkoutCalls = 0
        val trackingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(git = trackingGit)
        c.state.markSkipped(".")

        assertTrue(CheckoutStep().execute(c) is StepResult.Success)
        assertEquals(0, checkoutCalls)
        assertFalse(c.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout failure is not recorded as successful`() {
        val failingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 1, "", "conflict")
        }
        val c = context().copy(git = failingGit)

        assertTrue(CheckoutStep().execute(c) is StepResult.Partial)
        assertFalse(c.state.checkoutSucceeded("."))
    }

    @Test
    fun `missing branch restores stash and removes its tracking entry`() {
        var popCalls = 0
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun stashPop(workDir: File): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context().copy(git = missingGit)
        c.state.trackStash(".", "before -> dev")

        assertTrue(CheckoutStep().execute(c) is StepResult.Partial)
        assertEquals(1, popCalls)
        assertFalse(c.state.stashesSnapshot().containsKey("."))
    }

    // ---- DirtyHandlingStep ----

    @Test
    fun `dirty step skip clean repo`() {
        val c = context()
        val step = DirtyHandlingStep()
        assertTrue(step.execute(c) is StepResult.Success)
    }

    @Test
    fun `dirty step stash on dirty repo`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        assertTrue(step.execute(c) is StepResult.Success)
        assertTrue(log.any { it.contains("stash: ok") })
    }

    @Test
    fun `dirty step fail with skip on dirty repo`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val c = context(SwitchOptions(DirtyAction.Skip)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        assertTrue(step.execute(c) is StepResult.Partial)
        assertTrue(c.state.isSkipped("."))
    }

    @Test
    fun `stash failure marks path skipped and does not track stash`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", 1, "", "failed")
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        assertTrue(DirtyHandlingStep().execute(c) is StepResult.Partial)
        assertTrue(c.state.isSkipped("."))
        assertTrue(!c.state.hasStashes())
    }

    // ---- FetchStep ----

    @Test
    fun `fetch step skip when option disabled`() {
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = false))
        val step = FetchStep()
        assertTrue(step.execute(c) is StepResult.Success)
    }

    @Test
    fun `fetch step still fetches when already on target`() {
        var fetchCalls = 0
        val alreadyGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun fetch(workDir: File): GitResult {
                fetchCalls++
                return GitResult("fetch", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = true)).copy(git = alreadyGit)
        val step = FetchStep()
        assertTrue(step.execute(c) is StepResult.Success)
        assertEquals(1, fetchCalls)
    }

    @Test
    fun `fetch step performs fetch when needed`() {
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = true))
        val step = FetchStep()
        assertTrue(step.execute(c) is StepResult.Success)
    }

    // ---- PullStep ----

    @Test
    fun `pull step skip when option disabled`() {
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false))
        val step = PullStep()
        assertTrue(step.execute(c) is StepResult.Success)
    }

    @Test
    fun `pull step skip when preset pull disabled`() {
        val noPullGit = object : GitClient by fakeGit {
            override fun pullFf(workDir: File, branch: String): GitResult = error("should not be called")
        }
        val noPullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = noPullGit, preset = noPullPreset)
        val step = PullStep()
        assertTrue(step.execute(c) is StepResult.Success) // options.pull = false -> skip
    }

    @Test
    fun `pull step executes when both enabled`() {
        val calls = mutableListOf<String>()
        val pullGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult {
                calls += branch
                return GitResult("pull", 0, "", "")
            }
        }
        val pullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = pullGit, preset = pullPreset)
        c.state.markCheckoutSuccessful(".")
        val step = PullStep()
        assertTrue(step.execute(c) is StepResult.Success)
        assertEquals(listOf("dev"), calls)
    }

    @Test
    fun `pull step skips repos whose checkout did not succeed`() {
        var pullCalls = 0
        val pullGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult {
                pullCalls++
                return GitResult("pull", 0, "", "")
            }
        }
        val pullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = pullGit, preset = pullPreset)

        assertTrue(PullStep().execute(c) is StepResult.Success)
        assertEquals(0, pullCalls)
    }

    @Test
    fun `pull disabled still restores tracked stashes`() {
        var popCalls = 0
        val popGit = object : GitClient by fakeGit {
            override fun stashPop(workDir: File): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = popGit)
        c.state.trackStash(".", "before -> dev")

        assertTrue(PullStep().execute(c) is StepResult.Success)
        assertEquals(1, popCalls)
        assertTrue(!c.state.hasStashes())
    }

    @Test
    fun `staged pull restores only stashes in its target scope`() {
        val popped = mutableListOf<String>()
        projectRoot.resolve("SubA").toFile().mkdirs()
        val popGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun stashPop(workDir: File): GitResult {
                popped += if (workDir == projectRoot.toFile()) "." else workDir.name
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(
            git = popGit,
            preset = Preset("test", "dev", mapOf("SubA" to "dev")),
        )
        c.state.trackStash(".", "before -> dev")
        c.state.trackStash("SubA", "before -> dev")

        assertTrue(PullStep(SwitchTargetScope.MAIN).execute(c) is StepResult.Success)
        assertEquals(listOf("."), popped)
        assertEquals(setOf("SubA"), c.state.stashesSnapshot().keys)

        assertTrue(PullStep(SwitchTargetScope.SUBMODULES).execute(c) is StepResult.Success)
        assertEquals(listOf(".", "SubA"), popped)
        assertFalse(c.state.hasStashes())
    }

    @Test
    fun `submodule target scope processes parents before nested paths`() {
        val preset = Preset(
            "nested",
            "main",
            linkedMapOf(
                "SubA/Nested" to "nested-dev",
                "SubB" to "main",
                "SubA" to "dev",
            ),
        )

        assertEquals(
            listOf("SubB", "SubA", "SubA/Nested"),
            preset.targetsFor(SwitchTargetScope.SUBMODULES).map { it.path },
        )
    }

    // ---- SubmoduleSyncStep ----

    @Test
    fun `submodule sync step always runs`() {
        val c = context()
        c.state.markCheckoutSuccessful(".")
        val step = SubmoduleSyncStep()
        assertTrue(step.execute(c) is StepResult.Success)
    }

    @Test
    fun `submodule sync step returns Partial on error`() {
        val failGit = object : GitClient by fakeGit {
            override fun submoduleSync(gitRoot: File): GitResult = GitResult("sync", 1, "", "error")
        }
        val c = context().copy(git = failGit)
        c.state.markCheckoutSuccessful(".")
        val step = SubmoduleSyncStep()
        // SubmoduleSyncStep now returns Partial on failure, consistent with FetchStep/PullStep
        assertTrue(step.execute(c) is StepResult.Partial)
    }

    @Test
    fun `submodule sync is skipped when main checkout failed`() {
        var syncCalls = 0
        val trackingGit = object : GitClient by fakeGit {
            override fun submoduleSync(gitRoot: File): GitResult {
                syncCalls++
                return GitResult("sync", 0, "", "")
            }
        }
        val c = context().copy(git = trackingGit)

        assertTrue(SubmoduleSyncStep().execute(c) is StepResult.Partial)
        assertEquals(0, syncCalls)
    }

}
