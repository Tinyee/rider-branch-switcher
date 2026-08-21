package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.HeadAndBranch
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Rollback and recovery behavior of [SwitchExecutor] and [SwitchRecoveryExecutor]. */
class SwitchExecutorRollbackTest : SwitchExecutorTestBase() {

    @Test
    fun `rollback without checkpoint returns false`() {
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        ).copy(checkpoint = null)
        assertFalse("Rollback without checkpoint should return false", recovery().recover(result).rollbackOk)
    }

    @Test
    fun `recovery judges already-restored from one atomic head read`() {
        var atomicReads = 0
        var separateReads = 0
        val atomicGit = object : GitClient by fakeGit {
            override fun headAndBranch(workDir: File): HeadAndBranch? {
                atomicReads++
                return HeadAndBranch("main-sha", "main")
            }
            override fun currentBranch(workDir: File): String? {
                separateReads++
                return "other-branch"
            }
            override fun revParseHead(workDir: File): String? {
                separateReads++
                return "other-sha"
            }
        }
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = linkedMapOf("." to CheckpointEntry("main-sha", "main")),
            state = SwitchState(),
        )

        val outcome = recovery(atomicGit).recover(execution)

        assertTrue("an already-restored repo must not fail recovery", outcome.ok)
        assertEquals("HEAD and branch must be judged from one atomic read", 1, atomicReads)
        assertEquals("separate reads must not be consulted when the atomic read works", 0, separateReads)
    }

    @Test
    fun `recovery plan exposes repository stash and retained initialization actions`() {
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = linkedMapOf(
                "." to CheckpointEntry("main-sha", "main", "main-repository"),
                "SubA" to CheckpointEntry("sub-sha", null, "sub-repository"),
            ),
            state = SwitchState()
                .withTrackedStash("SubA", "before -> dev", "stash-oid")
                .withInitializedSubmodule("SubB"),
        )

        val plan = recovery().plan(execution)

        assertEquals(listOf(".", "SubA"), plan.repositories.map { it.repositoryPath })
        assertEquals(listOf("main-sha", "sub-sha"), plan.repositories.map { it.targetSha })
        assertEquals(listOf("SubA"), plan.stashes.map { it.repositoryPath })
        assertEquals(setOf("SubB"), plan.retainedInitializedSubmodules)
        assertTrue(plan.issues.isEmpty())
    }

    @Test
    fun `successful execution captures the checkpoint before mutation`() {
        var currentBranch = "main"
        val trackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                currentBranch = branch
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, trackGit)
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        val checkpoint = result.checkpoint
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertEquals("abc123", checkpoint["."]!!.sha)
        assertEquals(
            "checkpoint must record the pre-switch branch, not the target",
            "main",
            checkpoint["."]!!.branch,
        )
        assertEquals("the switch must have moved to the target branch", "dev", currentBranch)
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
        val result = executor.executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        checkoutCalls.clear()
        // Now branch = "dev", checkpoint has branch = "main" (recorded before switch)
        // Rollback should checkout "main"
        assertTrue(recovery(trackGit).recover(result).rollbackOk)
        assertTrue("Should call checkout for main branch, got: $checkoutCalls", "main" in checkoutCalls)
    }

    @Test
    fun `rollback falls back to checkpoint sha when branch restore fails`() {
        var currentBranch: String? = "main"
        val rollbackCalls = mutableListOf<String>()
        val rollbackGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (branch == "dev") {
                    currentBranch = "dev"
                    return GitResult("checkout", 0, "", "")
                }
                rollbackCalls += branch
                return if (branch == "abc123") {
                    currentBranch = null
                    GitResult("checkout", 0, "", "")
                } else {
                    GitResult("checkout", 1, "", "branch restore failed")
                }
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, rollbackGit)
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        val outcome = recovery(rollbackGit).recover(result)
        assertFalse("detached SHA fallback must not claim the named branch was restored", outcome.rollbackOk)
        assertEquals(OperationIssueCode.RECOVERY_FAILED, outcome.rollback.issues.single().code)
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
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        val recovery = recovery(rollbackGit)
        val recoveryResult = recovery.execute(recovery.plan(result))
        assertFalse(recoveryResult.ok)
        assertEquals(OperationIssueCode.CHECKOUT_FAILED, recoveryResult.issues.single().code)
    }

    @Test
    fun `rollback reports a stale index lock with an actionable message instead of writing`() {
        var currentBranch = "main"
        val switchGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (branch == "dev") {
                    currentBranch = "dev"
                }
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, switchGit)
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        assertTrue(result.ok)
        assertEquals("dev", currentBranch)

        // A lock appears after the switch (e.g. left behind by a killed git write);
        // recovery must surface it instead of failing on a checkout mystery.
        val lockedGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = currentBranch
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
        }
        val outcome = recovery(lockedGit).recover(result)
        assertFalse(outcome.rollbackOk)
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, outcome.rollback.issues.single().code)
        assertTrue(
            outcome.rollback.issues.single().diagnostic.orEmpty().contains("/repo/.git/index.lock"),
        )
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
        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        checkoutCalls.clear()

        assertTrue(recovery(detachedGit).recover(result).rollbackOk)
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
        val result = executor.executeResultTest(
            subPreset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertFalse(recovery(partialGit).recover(result).rollbackOk)
        assertTrue(rollbackCalls.contains("SubB" to "main"))
    }

    @Test
    fun `recovery restores stashes even when one repository rollback fails`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        val stashApplyCalls = mutableListOf<String>()
        val recoveryGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? =
                if (workDir == projectRoot.toFile()) "dev" else "main"

            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                if (workDir == projectRoot.toFile()) {
                    error("restore failed")
                } else {
                    GitResult("checkout", 0, "", "")
                }

            override fun stashApply(workDir: File, oid: String): GitResult {
                stashApplyCalls += workDir.name
                return GitResult("stash pop", 0, "", "")
            }
        }
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = mapOf(
                "." to CheckpointEntry("main-sha", "main"),
                "SubA" to CheckpointEntry("sub-sha", "main"),
            ),
            state = SwitchState().withTrackedStash("SubA", "before -> dev", "stash-oid"),
        )

        val outcome = recovery(recoveryGit).recover(execution)

        assertFalse(outcome.rollbackOk)
        assertTrue(outcome.stashRestore.issues.isEmpty())
        assertEquals(listOf("SubA"), stashApplyCalls)
        assertFalse(outcome.stashRestore.state.stashesSnapshot().isNotEmpty())
        // The applied stash is dropped after a clean restore, not retained as a backup.
        assertTrue(outcome.stashRestore.state.retainedStashBackupsSnapshot().isEmpty())
    }

    @Test
    fun `recovery stops between repositories when cancelled`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        initGitRepo(File(projectRoot.toFile(), "SubB"))
        var writes = 0
        val countingGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun resetHard(workDir: File, revision: String): GitResult {
                writes++
                return GitResult("reset", 0, "", "")
            }
        }
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = mapOf(
                "." to CheckpointEntry("main-sha", "main"),
                "SubA" to CheckpointEntry("subA-sha", "main"),
                "SubB" to CheckpointEntry("subB-sha", "main"),
            ),
            state = SwitchState(),
        )

        val outcome = SwitchRecoveryExecutor(
            projectRoot,
            createStringAppender { log += it },
            countingGit,
            cancelled = { writes >= 1 },
        ).recover(execution)

        assertEquals("recovery must stop after the first write when cancelled", 1, writes)
        assertFalse("a cancelled rollback is not complete", outcome.rollbackOk)
        assertEquals("the remaining repositories are not reported as restored", 1, outcome.rollback.outcomes.size)
    }

    @Test
    fun `rollback reports failure when repo is missing`() {
        val skipGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 0, "", "")
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, skipGit)
        // Execute a switch so checkpoint is recorded
        val execution = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        // Delete the .git dir to simulate missing repo
        File(projectRoot.toFile(), ".git").deleteRecursively()
        val result = recovery(skipGit).recover(execution).rollbackOk
        assertFalse("Rollback cannot report success when a repo was not restored", result)
    }

    @Test
    fun `rollback refuses a repository that replaced the checkpointed worktree`() {
        val submodule = projectRoot.resolve("SubA").toFile()
        initGitRepo(submodule)
        var checkoutCalls = 0
        var resetCalls = 0
        val replacedGit = object : GitClient by fakeGit {
            override fun repositoryIdentity(workDir: File): RepositoryIdentity =
                RepositoryIdentity("replacement-repository", projectRoot.toString())

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }

            override fun resetHard(workDir: File, revision: String): GitResult {
                resetCalls++
                return GitResult("reset", 0, "", "")
            }
        }
        val result = SwitchExecutionResult(
            status = SwitchExecutionStatus.PARTIAL,
            checkpoint = mapOf("SubA" to CheckpointEntry("before", "main", "original-repository")),
            state = SwitchState(),
        )

        assertFalse(recovery(replacedGit).recover(result).rollbackOk)
        assertEquals(0, checkoutCalls)
        assertEquals(0, resetCalls)
    }

    @Test
    fun `rollback skips when branch and HEAD already match checkpoint`() {
        var resetCalls = 0
        val matchingGit = object : GitClient by fakeGit {
            override fun resetHard(workDir: File, revision: String): GitResult {
                resetCalls++
                return GitResult("reset", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, fakeGit)
        val execution = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        val result = recovery(matchingGit).recover(execution).rollbackOk
        assertTrue("Rollback should succeed when branch and HEAD match", result)
        assertEquals(0, resetCalls)
    }

    @Test
    fun `rollback resets same branch when HEAD advanced after checkpoint`() {
        var currentSha = "before"
        val resetCalls = mutableListOf<String>()
        val advancedGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun revParseHead(workDir: File): String? = currentSha
            override fun resetHard(workDir: File, revision: String): GitResult {
                resetCalls += revision
                currentSha = revision
                return GitResult("reset", 0, "", "")
            }
        }
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = mapOf("." to CheckpointEntry("before", "main")),
            state = SwitchState(),
        )
        currentSha = "after"

        assertTrue(recovery(advancedGit).recover(execution).rollbackOk)
        assertEquals(listOf("before"), resetCalls)
        assertEquals("before", currentSha)
    }

    @Test
    fun `rollback refuses hard reset when working tree is dirty`() {
        var resetCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "main"
            override fun revParseHead(workDir: File): String? = "after"
            override fun isDirty(workDir: File): Boolean = true
            override fun resetHard(workDir: File, revision: String): GitResult {
                resetCalls++
                return GitResult("reset", 0, "", "")
            }
        }
        val execution = SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = mapOf("." to CheckpointEntry("before", "main")),
            state = SwitchState(),
        )

        val recovery = recovery(dirtyGit)
        val recoveryResult = recovery.execute(recovery.plan(execution))
        assertFalse(recoveryResult.ok)
        assertEquals(0, resetCalls)
        assertEquals(OperationIssueCode.WORKTREE_DIRTY, recoveryResult.issues.single().code)
    }
}
