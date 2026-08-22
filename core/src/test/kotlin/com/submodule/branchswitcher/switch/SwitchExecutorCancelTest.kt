package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.executeTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** Cancellation, stash tracking, and fail-closed submodule initialization of [SwitchExecutor]. */
class SwitchExecutorCancelTest : SwitchExecutorTestBase() {

    @Test
    fun `cancel after one step stops remaining pipeline and signals git`() {
        var cancelled = false
        var cancelCalls = 0
        var stashCalls = 0
        var checkoutCalls = 0
        val trackingGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                cancelled = true // the cancel request lands while the first real step is working
                return GitResult("stash", 0, "", "")
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }

            override fun cancel() {
                cancelCalls++
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            trackingGit,
            cancelled = { cancelled },
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertEquals("DirtyHandlingStep is the first real step and must run once", 1, stashCalls)
        assertEquals("no step after the cancelled one may run", 0, checkoutCalls)
        assertEquals(1, cancelCalls)
        assertTrue(log.any { it.contains("[cancelled] before step: checkout main") })
    }

    @Test
    fun `cancellation inside dirty step retains stash state for recovery`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        var stashRecorded = false
        var cancelCalls = 0
        val cancellation = object : CancellationHandle {
            override fun checkCanceled() {
                // Event-driven: cancel on the first check after the WIP stash was recorded,
                // so the test always covers the post-stash window rather than a check count.
                if (stashRecorded) throw CancellationException("cancel after stash")
            }

            override val isCanceled = false
        }
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashRecorded = true
                return GitResult("stash", 0, "", "")
            }

            override fun cancel() {
                cancelCalls++
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            cancellationHandle = cancellation,
        )

        val result = executor.executeResultTest(
            Preset("sub", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue(result.cancelled)
        assertEquals(setOf("."), result.state.stashesSnapshot().map { it.repositoryPath }.toSet())
        assertEquals(1, cancelCalls)
    }

    @Test
    fun `failed step keeps stashes tracked so recovery restores them after rollback`() {
        var stashApplyCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            // The checkout query aborting is the failing step: a plain RuntimeException
            // escaping the checkout, folded into a STEP_FAILED result by the executor.
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                error("checkout query failed")

            override fun stashApply(workDir: File, oid: String): GitResult {
                stashApplyCalls++
                return GitResult("stash pop", 0, "", "")
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
        )
        val result = executor.executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        // A FAILED pipeline must not apply the WIP before the rollback: restoring it
        // first would dirty the trees and block the recovery's clean-tree requirement.
        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        assertEquals(0, stashApplyCalls)
        assertEquals("failed switches must keep stashes tracked for recovery", setOf("."), result.state.stashesSnapshot().map { it.repositoryPath }.toSet())
        assertTrue(result.state.retainedStashBackupsSnapshot().isEmpty())

        // Recovery rolls the repositories back first, then restores the WIP.
        val outcome = recovery(dirtyGit).recover(result)
        assertTrue("rollback-then-restore must succeed", outcome.rollbackOk)
        assertEquals(1, stashApplyCalls)
        assertTrue(outcome.stashRestore.state.stashesSnapshot().isEmpty())
    }

    @Test
    fun `Git exception inside dirty step returns failed execution with latest state`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean {
                if (workDir.name == "SubA") error("query failed")
                return true
            }
        }
        // DirtyHandlingStep inspects the main repo only; the SubA probe now runs inside
        // SubmoduleTreeStep (after the topology gate), so the default steps surface the
        // exception there with the CHECKOUT stage.
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
        )

        val result = executor.executeResultTest(
            Preset("sub", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        assertNotNull(result.checkpoint)
        assertTrue("failed switch keeps the stash tracked for recovery", setOf(".") == result.state.stashesSnapshot().map { it.repositoryPath }.toSet())
        assertTrue(result.state.retainedStashBackupsSnapshot().isEmpty())
        val issue = result.issues.single()
        assertEquals(OperationStage.CHECKOUT, issue.stage)
        assertEquals(OperationIssueCode.STEP_FAILED, issue.code)
        assertTrue(issue.diagnostic.orEmpty().contains("query failed"))
    }

    @Test
    fun `main is pulled before missing submodule is synced initialized and fetched`() {
        val events = mutableListOf<String>()
        var mainPulled = false
        var submoduleReady = false
        var submoduleBranch: String? = null
        val stagedGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean =
                if (workDir.name == "SubA") submoduleReady else true

            override fun currentBranch(workDir: File): String? =
                if (workDir.name == "SubA") submoduleBranch else "main"

            override fun fetch(workDir: File): GitResult {
                events += "fetch:${if (workDir.name == "SubA") "SubA" else "."}"
                return GitResult("fetch", 0, "", "")
            }

            override fun pullFf(workDir: File, branch: String): GitResult {
                val path = if (workDir.name == "SubA") "SubA" else "."
                events += "pull:$path"
                if (path == ".") mainPulled = true
                return GitResult("pull", 0, "", "")
            }

            override fun submoduleSync(gitRoot: File): GitResult {
                assertTrue("main must be pulled before submodule sync", mainPulled)
                events += "sync"
                return GitResult("sync", 0, "", "")
            }

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                assertTrue("main must be pulled before submodule init", mainPulled)
                events += "init:$path"
                gitRoot.resolve(path).mkdirs()
                submoduleReady = true
                return GitResult("init", 0, "", "")
            }

            override fun localBranchExists(workDir: File, branch: String): Boolean =
                workDir.name != "SubA"

            override fun checkoutFromRemote(workDir: File, branch: String): GitResult {
                events += "checkout-remote:SubA"
                submoduleBranch = branch
                return GitResult("checkout", 0, "", "")
            }
        }
        val subPreset = Preset("cloud-submodule", "main", mapOf("SubA" to "release"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, stagedGit)

        val result = executor.executeTest(
            subPreset,
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true),
        )

        assertTrue("Cloud-only submodule switch should succeed", result)
        assertEquals(
            listOf(
                "fetch:.",
                "pull:.",
                "sync",
                "init:SubA",
                "fetch:SubA",
                "checkout-remote:SubA",
                "pull:SubA",
            ),
            events,
        )
    }

    @Test
    fun `initialized submodule is retained and reported when its fetch is cancelled`() {
        var submoduleReady = false
        val retainedGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean =
                if (workDir.name == "SubA") submoduleReady else true

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                gitRoot.resolve(path).mkdirs()
                submoduleReady = true
                return GitResult("init", 0, "", "")
            }

            override fun fetch(workDir: File): GitResult {
                if (workDir.name == "SubA") throw CancellationException("cancel after init")
                return GitResult("fetch", 0, "", "")
            }

            override fun cancel() = Unit
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            retainedGit,
            cancellationClassifier = CancellationClassifier.DEFAULT,
        )

        val result = executor.executeResultTest(
            Preset("retained-init", "main", mapOf("SubA" to "main")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true),
        )

        assertTrue(result.cancelled)
        assertEquals(setOf("SubA"), result.state.initializedSubmodulesSnapshot())
        assertTrue(File(projectRoot.toFile(), "SubA").exists())

        assertTrue(recovery(retainedGit).recover(result).rollbackOk)
        assertTrue(File(projectRoot.toFile(), "SubA").exists())
        assertTrue(log.any { it.contains("[rollback] retained submodule initialized by this switch: SubA") })
    }

    // -- confirmBeforeInit fail-closed ---------------------------------

    @Test
    fun `confirmBeforeInit with unrelated pre-approval declines init`() {
        val noInitGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = when {
                workDir.name == "SubA" -> false
                else -> true
            }
            override fun listSubmodulePaths(gitRoot: File): List<String> = listOf("SubA")
        }
        val subPreset = Preset("sub", "main", mapOf("SubA" to "main"))
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noInitGit,
            preApprovedSubmoduleInit = setOf("Other"))
        val result = executor.executeTest(subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true))
        assertFalse("Switch should have partial failure from declined init", result)
        assertTrue("Should log init declined", log.any { it.contains("[skip] init declined for SubA") })
    }

    @Test
    fun `confirmBeforeInit with matching pre-approval proceeds with init`() {
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
            preApprovedSubmoduleInit = setOf("SubA"))
        val result = executor.executeTest(subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true))
        assertTrue("Switch should succeed", result)
        assertEquals(listOf("init:SubA"), initLog)
    }

    @Test
    fun `cancellation aborts submodule init before an unapproved path can be initialized`() {
        var initCalls = 0
        var isGitRepoCalls = 0
        val cancelled = object : CancellationHandle {
            override fun checkCanceled() = throw CancellationException("cancelled")
            override val isCanceled = true
        }
        val git = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean {
                isGitRepoCalls++
                return false // SubA needs init
            }
            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                initCalls++
                return GitResult("init", 0, "", "")
            }
        }
        val context = SwitchContext(
            projectRoot = projectRoot,
            preset = Preset("sub", "main", mapOf("SubA" to "main")),
            options = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = true),
            git = git,
            log = createStringAppender { },
            cancellationHandle = cancelled,
            preApprovedSubmoduleInit = emptySet(),
        )
        val target = RepoTarget("SubA", "main")
        assertThrows(CancellationException::class.java) {
            SubmoduleInitializer.prepare(
                context,
                target,
                File(projectRoot.toFile(), "SubA"),
                projectRoot.toFile(),
                "SubA",
            )
        }
        assertEquals("cancellation must abort before any Git query", 0, isGitRepoCalls)
        assertEquals("unapproved submodule must not be initialized under cancellation", 0, initCalls)
    }

    @Test
    fun `shortLabel extracts basename and strips display suffix`() {
        assertEquals("SubA", shortLabel("lib/SubA"))
        assertEquals("main", shortLabel("main"))
        assertEquals("repo", shortLabel("a/b/c/repo"))
        assertEquals("SubA", shortLabel("lib/SubA~"))
        assertEquals("x", shortLabel("x~"))
    }
}
