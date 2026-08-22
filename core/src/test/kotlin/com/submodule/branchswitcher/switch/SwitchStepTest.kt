package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.IndexLockBlockedException
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
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
        override fun isSubmoduleOnlyDirty(workDir: File): Boolean = false
        override fun indexLockFile(workDir: File): String? = null
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashTopOid(workDir: File): String = "stash-oid"
        override fun stashApply(workDir: File, oid: String): GitResult = GitResult("pop", 0, "", "")
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
        override fun repositoryIdentity(workDir: File): RepositoryIdentity {
            val root = projectRoot.toFile().canonicalFile
            val sectionName = workDir.name
            val gitDirectory = if (workDir.canonicalFile == root) {
                File(root, ".git")
            } else {
                File(root, ".git/modules/$sectionName")
            }
            return RepositoryIdentity(gitDirectory.absolutePath, root.takeIf { workDir.canonicalFile != root }?.path)
        }
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
            SubmoduleRegistration("SubA", "SubA", "."),
        )
        override fun resetHard(workDir: File, revision: String): GitResult = GitResult("reset", 0, "", "")
        override fun cancel() = Unit
    }

    private fun context(opts: SwitchOptions = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)) =
        SwitchContext(projectRoot, Preset("test", "dev"), opts, fakeGit, createStringAppender { log += it })

    private fun SwitchStep.run(
        context: SwitchContext,
        state: SwitchState = SwitchState(),
    ): StepExecution = execute(context, state)

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
    fun `checkout step skip when already on target branch`() {
        val sameGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
        }
        val c = context().copy(git = sameGit)
        val step = CheckoutStep()
        val execution = step.run(c)
        assertTrue(execution.result is StepResult.Success)
        assertTrue(log.any { it.contains("already on") })
        assertTrue(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout step fails when branch not found`() {
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        }
        val c = context().copy(git = missingGit)
        val step = CheckoutStep()
        assertTrue(step.run(c).result is StepResult.Partial)
    }

    @Test
    fun `checkout step creates from remote when local missing`() {
        var remoteCheckoutCalls = 0
        val remoteOnlyGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = true
            override fun checkoutFromRemote(workDir: File, branch: String): GitResult {
                remoteCheckoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(git = remoteOnlyGit)
        val step = CheckoutStep()
        assertTrue(step.run(c).result is StepResult.Success)
        assertEquals(1, remoteCheckoutCalls)
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
        val state = SwitchState().withSkipped(".")

        val execution = CheckoutStep().run(c, state)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(0, checkoutCalls)
        assertFalse(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `checkout failure is not recorded as successful`() {
        val failingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 1, "", "conflict")
        }
        val c = context().copy(git = failingGit)

        val execution = CheckoutStep().run(c)
        assertTrue(execution.result is StepResult.Partial)
        assertFalse(execution.state.checkoutSucceeded("."))
    }

    @Test
    fun `missing branch keeps the stash tracked for pipeline-level restore`() {
        var popCalls = 0
        val missingGit = object : GitClient by fakeGit {
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun stashApply(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context().copy(git = missingGit)
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val execution = CheckoutStep().run(c, state)
        assertTrue(execution.result is StepResult.Partial)
        assertEquals("checkout must not restore the stash before the outcome is final", 0, popCalls)
        assertTrue("stash stays tracked so the executor restores it afterwards", execution.state.stashesSnapshot().any { it.repositoryPath == "." })
    }

    // ---- DirtyHandlingStep ----

    @Test
    fun `dirty step leaves a clean repo untouched`() {
        val c = context()
        val execution = DirtyHandlingStep().run(c, SwitchState())

        assertTrue(execution.result is StepResult.Success)
        assertFalse(execution.state.isSkipped("."))
        assertTrue(execution.state.stashesSnapshot().isEmpty())
    }

    @Test
    fun `dirty step stash on dirty repo`() {
        var stashCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                return GitResult("stash", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        val execution = step.run(c)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(1, stashCalls)
        assertTrue(execution.state.stashesSnapshot().isNotEmpty())
        assertTrue(log.any { it.contains("stash: ok") })
    }

    @Test
    fun `dirty step stashes even when already on target branch`() {
        var stashCalls = 0
        val onTargetDirtyGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                return GitResult("stash", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = onTargetDirtyGit)

        val execution = DirtyHandlingStep().run(c)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(1, stashCalls)
        assertTrue(execution.state.stashesSnapshot().isNotEmpty())
    }

    @Test
    fun `terminated stash with a created entry is tracked for recovery`() {
        var pushRan = false
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashTopOid(workDir: File): String? =
                if (pushRan) "created-oid" else "before-oid"
            override fun stash(workDir: File, message: String): GitResult {
                pushRan = true
                return GitResult("stash", -1, "", "cancelled")
            }
            override fun stashOidByMessage(workDir: File, messagePrefix: String): String? =
                if (pushRan) "created-oid" else null
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)

        assertTrue(execution.result is StepResult.Success)
        val stash = execution.state.stashesSnapshot().firstOrNull { it.repositoryPath == "." }
        assertNotNull("a terminated stash that created an entry must be tracked", stash)
        assertEquals("created-oid", stash?.oid)
    }

    @Test
    fun `failed pre-push stash top read logs a warning`() {
        var reads = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashTopOid(workDir: File): String? {
                reads++
                if (reads == 1) throw GitQueryException(GitResult("rev-parse", 1, "", "index.lock exists"))
                return "stash-oid"
            }
            override fun stashOidByMessage(workDir: File, messagePrefix: String): String? = "stash-oid"
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)

        assertTrue(execution.result is StepResult.Success)
        assertTrue(
            "pre-push read failure must be logged",
            log.any { it.contains("could not read stash top") },
        )
        assertEquals("stash-oid", execution.state.stashesSnapshot().firstOrNull { it.repositoryPath == "." }?.oid)
    }

    @Test
    fun `failed ghost stash inspection logs a warning`() {
        var reads = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashTopOid(workDir: File): String? {
                reads++
                // Pre-push read succeeds; the post-termination inspection throws.
                if (reads == 1) return "before-oid"
                throw GitQueryException(GitResult("rev-parse", 1, "", "index.lock exists"))
            }
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", -1, "", "cancelled")
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(
            "ghost inspection failure must be logged",
            log.any { it.contains("torn stash") },
        )
        assertTrue(execution.state.isSkipped("."))
        assertNull("an unverifiable entry must not be tracked", execution.state.trackedStash("."))
    }

    @Test
    fun `terminated stash that did not advance the stack is not tracked`() {
        // The push died before writing refs/stash, but an older backup still sits on
        // top. That backup must not be mistaken for the current WIP (H2).
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", -1, "", "cancelled")
            // stashTopOid stays "stash-oid" throughout.
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertNull("an old backup must not be tracked as the current WIP", execution.state.trackedStash("."))
        assertEquals(
            OperationIssueCode.STASH_FAILED,
            (execution.result as StepResult.Partial).issues.single().code,
        )
    }

    @Test
    fun `terminated stash without an entry is reported as failed`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", -1, "", "interrupted")
            override fun stashTopOid(workDir: File): String? = null
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertEquals(
            OperationIssueCode.STASH_FAILED,
            (execution.result as StepResult.Partial).issues.single().code,
        )
    }

    @Test
    fun `dirty step retains an unidentified stash but blocks further writes`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashTopOid(workDir: File): String? = null
        }
        val execution = DirtyHandlingStep().run(
            context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit),
        )

        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertNull(execution.state.trackedStash(".")?.oid)
        val issue = (execution.result as StepResult.Partial).issues.single()
        assertEquals(OperationIssueCode.STASH_IDENTITY_UNAVAILABLE, issue.code)
    }

    @Test
    fun `dirty step fail with skip on dirty repo`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }
        val c = context(SwitchOptions(DirtyAction.Skip)).copy(git = dirtyGit)
        val step = DirtyHandlingStep()
        val initialState = SwitchState()
        val execution = step.run(c, initialState)
        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertFalse(initialState.isSkipped("."))
    }

    @Test
    fun `stash failure marks path skipped and does not track stash`() {
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", 1, "", "failed")
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = dirtyGit)

        val execution = DirtyHandlingStep().run(c)
        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        assertFalse(execution.state.stashesSnapshot().isNotEmpty())
    }

    @Test
    fun `submodule-only dirt proceeds without stashing`() {
        var stashCalls = 0
        val subOnlyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun isSubmoduleOnlyDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                return GitResult("stash", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = subOnlyGit)

        val execution = DirtyHandlingStep().run(c)
        assertTrue(execution.result is StepResult.Success)
        assertEquals(0, stashCalls)
        assertFalse(execution.state.isSkipped("."))
        assertTrue(log.any { it.contains("submodule") })
    }

    @Test
    fun `stash failure surfaces a stale index lock as an actionable hint`() {
        val lockGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
            override fun stash(workDir: File, message: String): GitResult =
                GitResult("stash", 1, "", "")
        }
        val c = context(SwitchOptions(DirtyAction.Stash)).copy(git = lockGit)

        val execution = DirtyHandlingStep().run(c)
        assertTrue(execution.result is StepResult.Partial)
        assertTrue(execution.state.isSkipped("."))
        val issue = (execution.result as StepResult.Partial).issues.single()
        assertEquals(OperationIssueCode.STASH_FAILED, issue.code)
        val diagnostic = issue.diagnostic.orEmpty()
        assertTrue(diagnostic.contains("index.lock"))
        assertTrue(diagnostic.contains("/repo/.git/index.lock"))
    }

    // ---- FetchStep ----

    @Test
    fun `fetch step skip when option disabled`() {
        val noFetchGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult = error("fetch should not be called")
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = false)).copy(git = noFetchGit)
        val step = FetchStep()
        assertTrue(step.run(c).result is StepResult.Success)
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
        assertTrue(step.run(c).result is StepResult.Success)
        assertEquals(1, fetchCalls)
    }

    @Test
    fun `fetch failure is reported as partial`() {
        val failingGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult =
                GitResult("fetch", 1, "", "network unavailable")
        }
        val c = context(SwitchOptions(DirtyAction.Stash, fetchFirst = true)).copy(git = failingGit)

        val result = FetchStep().run(c).result

        assertEquals(
            listOf("." to OperationIssueCode.FETCH_FAILED),
            (result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
    }

    // ---- PullStep ----

    @Test
    fun `pull step skip when preset pull disabled`() {
        val noPullGit = object : GitClient by fakeGit {
            override fun pullFf(workDir: File, branch: String): GitResult = error("should not be called")
        }
        val noPullPreset = Preset("test", "dev", emptyMap())
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = noPullGit, preset = noPullPreset)
        val step = PullStep()
        assertTrue(step.run(c).result is StepResult.Success) // options.pull = false -> skip
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
        val state = SwitchState().withSuccessfulCheckout(".")
        val step = PullStep()
        assertTrue(step.run(c, state).result is StepResult.Success)
        assertEquals(listOf("dev"), calls)
    }

    @Test
    fun `pull failure is partial and leaves the stash tracked for the executor`() {
        var popCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult =
                GitResult("pull", 1, "", "not fast-forward")

            override fun stashApply(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = true)).copy(git = failingGit)
        val state = SwitchState()
            .withSuccessfulCheckout(".")
            .withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "main -> dev", "stash-oid")

        val execution = PullStep().run(c, state)

        assertEquals(
            listOf("." to OperationIssueCode.PULL_FAILED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals("a partial outcome must not restore the stash before the pipeline ends", 0, popCalls)
        assertTrue(execution.state.stashesSnapshot().isNotEmpty())
    }

    @Test
    fun `stash restore skips a stale index lock with an actionable issue`() {
        var popCalls = 0
        val lockedGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult = GitResult("pull", 0, "", "")
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
            override fun stashApply(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val state = SwitchState()
            .withSuccessfulCheckout(".")
            .withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "main -> dev", "stash-oid")

        val restore = restoreTrackedStashes(
            projectRoot, lockedGit, createStringAppender { log += it }, state,
        )

        val issue = restore.issues.single()
        assertEquals("." to OperationIssueCode.INDEX_LOCK_BLOCKING, issue.repositoryPath to issue.code)
        assertEquals("/repo/.git/index.lock", issue.lockPath)
        assertTrue(issue.diagnostic.orEmpty().contains("delete it and retry"))
        assertEquals("apply must not run on a locked repository", 0, popCalls)
        // Not marked restore-attempted, so a later recovery retries the apply.
        assertFalse(restore.state.stashesSnapshot().firstOrNull { it.repositoryPath == "." }?.restoreAttempted ?: true)
    }

    @Test
    fun `stash apply blocked by a lock appearing mid-restore reports the lock`() {
        var lockChecks = 0
        val racedLockGit = object : GitClient by fakeGit {
            override fun indexLockFile(workDir: File): String? {
                lockChecks++
                return if (lockChecks == 1) null else "/repo/.git/index.lock"
            }
            override fun stashApply(workDir: File, oid: String): GitResult =
                GitResult("pop", 1, "", "failed")
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val restore = restoreTrackedStashes(
            projectRoot, racedLockGit, createStringAppender { log += it }, state,
        )

        val issue = restore.issues.single()
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, issue.code)
        assertEquals("/repo/.git/index.lock", issue.lockPath)
        assertTrue(issue.diagnostic.orEmpty().contains("delete it and retry"))
    }

    @Test
    fun `terminated apply with a leftover lock is not treated as a lock race`() {
        var lockChecks = 0
        val terminatedGit = object : GitClient by fakeGit {
            override fun indexLockFile(workDir: File): String? {
                lockChecks++
                return if (lockChecks == 1) null else "/repo/.git/index.lock"
            }
            override fun stashApply(workDir: File, oid: String): GitResult =
                GitResult("pop", -1, "", "cancelled")
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")
        var cancelledCalls = 0
        // Cancelled only mid-apply: the loop-top check must let the apply start, then
        // the user cancels while git is applying the stash.
        val cancelMidApply = object : OperationControl {
            override fun checkCancelled() = Unit
            override val isCanceled: Boolean
                get() {
                    cancelledCalls++
                    return cancelledCalls > 1
                }
        }

        val restore = restoreTrackedStashes(
            projectRoot, terminatedGit, createStringAppender { log += it }, state,
            control = cancelMidApply,
        )

        // Termination must win over the raced-lock branch: the leftover lock is the
        // terminated apply's own, so it must not surface as a lock race.
        assertEquals(OperationIssueCode.STASH_RESTORE_FAILED, restore.issues.single().code)
        assertTrue("a user-cancelled termination suppresses automatic retry", restore.interrupted)
        assertFalse("WIP stays retryable (apply never completed)", restore.state.trackedStash(".")?.restoreAttempted ?: true)
    }

    @Test
    fun `apply interrupted mid-restore by user cancel suppresses automatic retry`() {
        val terminatedGit = object : GitClient by fakeGit {
            override fun stashApply(workDir: File, oid: String): GitResult =
                GitResult("pop", -1, "", "cancelled")
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")
        var cancelledCalls = 0
        val cancelMidApply = object : OperationControl {
            override fun checkCancelled() = Unit
            override val isCanceled: Boolean
                get() {
                    cancelledCalls++
                    return cancelledCalls > 1
                }
        }

        val restore = restoreTrackedStashes(
            projectRoot, terminatedGit, createStringAppender { log += it }, state,
            control = cancelMidApply,
        )

        assertTrue("an apply-time cancel must mark the restore interrupted", restore.interrupted)
        assertEquals(OperationIssueCode.STASH_RESTORE_FAILED, restore.issues.single().code)
    }

    @Test
    fun `timeout termination without user cancel stays retryable`() {
        val timeoutGit = object : GitClient by fakeGit {
            override fun stashApply(workDir: File, oid: String): GitResult =
                GitResult("pop", -1, "", "timeout after 10s")
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val restore = restoreTrackedStashes(
            projectRoot, timeoutGit, createStringAppender { log += it }, state,
        )

        assertFalse("a timeout is not an explicit user cancel", restore.interrupted)
        assertFalse(restore.state.trackedStash(".")?.restoreAttempted ?: true)
    }

    @Test
    fun `skipping a parent disables its nested targets`() {
        projectRoot.resolve("Parent").toFile().mkdirs()
        val c = context(SwitchOptions(DirtyAction.Skip, pull = false)).copy(
            git = object : GitClient by fakeGit {
                override fun isDirty(workDir: File): Boolean = workDir.name == "Parent"
                override fun isGitRepo(workDir: File): Boolean = true
            },
            preset = Preset("nested", "dev", mapOf("Parent" to "dev", "Parent/Nested" to "dev")),
        )
        val initial = SwitchState().withSkipped("Parent")

        val execution = SubmoduleTreeStep().execute(c, initial)

        assertTrue(execution.state.isSkipped("Parent"))
        assertTrue("a skipped parent must protect nested targets", execution.state.isSkipped("Parent/Nested"))
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

        assertTrue(PullStep().run(c).result is StepResult.Success)
        assertEquals(0, pullCalls)
    }

    @Test
    fun `pull disabled leaves stash restoration to the end of the pipeline`() {
        var popCalls = 0
        val popGit = object : GitClient by fakeGit {
            override fun stashApply(workDir: File, oid: String): GitResult {
                popCalls++
                return GitResult("pop", 0, "", "")
            }
        }
        val c = context(SwitchOptions(DirtyAction.Stash, pull = false)).copy(git = popGit)
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val execution = PullStep().run(c, state)
        assertTrue(execution.result is StepResult.Success)
        assertEquals("PullStep no longer restores stashes; the executor does at the end", 0, popCalls)
        assertTrue(execution.state.stashesSnapshot().isNotEmpty())
        assertTrue(execution.state.retainedStashBackupsSnapshot().isEmpty())
    }

    @Test
    fun `stash restore guard lock remains retryable when guard throws`() {
        val guardedGit = object : GitClient by fakeGit {
            override fun stashApply(workDir: File, oid: String): GitResult =
                throw IndexLockBlockedException(projectRoot.toFile(), "/repo/.git/index.lock")
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val restore = restoreTrackedStashes(
            projectRoot, guardedGit, createStringAppender { log += it }, state,
        )

        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, restore.issues.single().code)
        assertFalse(restore.state.trackedStash(".")?.restoreAttempted ?: true)
    }

    @Test
    fun `restore interrupted by cancel is reported as interrupted`() {
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")
        val cancelled = object : OperationControl {
            override fun checkCancelled() = Unit
            override val isCanceled: Boolean = true
        }

        val restore = restoreTrackedStashes(
            projectRoot,
            fakeGit,
            createStringAppender { log += it },
            state,
            control = cancelled,
        )

        assertTrue("a cancel-stopped restore must be marked interrupted", restore.interrupted)
        assertEquals("WIP stays tracked for a later explicit retry", "stash-oid", restore.state.trackedStash(".")?.oid)
        assertTrue(restore.issues.isEmpty())
    }

    @Test
    fun `stash apply failure is retained but not automatically replayed`() {
        var applyCalls = 0
        val popGit = object : GitClient by fakeGit {
            override fun stashApply(workDir: File, oid: String): GitResult {
                applyCalls++
                return GitResult("pop", 1, "", "conflict")
            }
        }
        val state = SwitchState().withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val first = restoreTrackedStashes(projectRoot, popGit, createStringAppender { log += it }, state)
        val second = restoreTrackedStashes(projectRoot, popGit, createStringAppender { log += it }, first.state)

        assertEquals(OperationIssueCode.STASH_RESTORE_FAILED, first.issues.single().code)
        assertEquals(OperationIssueCode.STASH_RESTORE_FAILED, second.issues.single().code)
        assertEquals("a failed apply is not replayed", 1, applyCalls)
        assertTrue(second.state.stashesSnapshot().isNotEmpty())
        assertTrue(second.state.trackedStash(".")?.restoreAttempted == true)
        assertTrue(second.state.retainedStashBackupsSnapshot().isEmpty())
    }

    @Test
    fun `restore honors a selected-path scope for staged recovery`() {
        val popped = mutableListOf<String>()
        projectRoot.resolve("SubA").toFile().mkdirs()
        val popGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                popped += if (workDir == projectRoot.toFile()) "." else workDir.name
                return GitResult("pop", 0, "", "")
            }
        }
        val initialState = SwitchState()
            .withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")
            .withTrackedStash("SubA", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "before -> dev", "stash-oid")

        val mainRestore = restoreTrackedStashes(
            projectRoot, popGit, createStringAppender { log += it }, initialState, setOf("."),
        )
        assertEquals(listOf("."), popped)
        assertEquals(setOf("SubA"), mainRestore.state.stashesSnapshot().map { it.repositoryPath }.toSet())

        val submoduleRestore = restoreTrackedStashes(
            projectRoot, popGit, createStringAppender { log += it }, mainRestore.state, setOf("SubA"),
        )
        assertEquals(listOf(".", "SubA"), popped)
        assertFalse(submoduleRestore.state.stashesSnapshot().isNotEmpty())
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

    @Test
    fun `failed submodule initialization prevents checkout and is not tracked as initialized`() {
        var checkoutCalls = 0
        val failingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = workDir.name != "SubA"

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
                GitResult("init", 1, "", "clone failed")

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = failingGit,
            preset = Preset("test", "main", mapOf("SubA" to "dev")),
        )
        val state = SwitchState().withSuccessfulCheckout(".")

        val execution = SubmoduleTreeStep().run(c, state)

        assertEquals(
            listOf("SubA" to OperationIssueCode.SUBMODULE_INIT_FAILED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.initializedSubmodulesSnapshot().isEmpty())
        assertFalse(execution.state.checkoutSucceeded("SubA"))
    }

    @Test
    fun `successful init command without a usable repository fails its target locally`() {
        var checkoutCalls = 0
        val incompleteGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = workDir.name != "SubA"
            override fun submoduleInitPath(gitRoot: File, path: String): GitResult =
                GitResult("init", 0, "", "")

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val execution = SubmoduleTreeStep().run(
            context().copy(
                git = incompleteGit,
                preset = Preset("test", "main", mapOf("SubA" to "dev")),
            ),
            SwitchState().withSuccessfulCheckout("."),
        )

        assertEquals(
            listOf("SubA" to OperationIssueCode.SUBMODULE_DIRECTORY_MISSING),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.initializedSubmodulesSnapshot().isEmpty())
    }

    @Test
    fun `obsolete preset path is skipped after main checkout`() {
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("moved", "main", mapOf("OldSub" to "dev")),
        )

        val execution = SubmoduleTreeStep().run(
            c,
            SwitchState().withSuccessfulCheckout("."),
        )

        assertEquals(
            listOf("OldSub" to OperationIssueCode.SUBMODULE_NOT_REGISTERED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
        assertTrue(execution.state.isSkipped("OldSub"))
        assertTrue(log.any { it.contains("obsolete worktree retained") })
    }

    @Test
    fun `unregistered preset path is never fetched stashed or isolated`() {
        // Spec 4: an unregistered/obsolete worktree must not be scanned, stashed, or
        // isolated. If any of these writes is reached, the test fails loudly instead of
        // silently skipping.
        val noTouchGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult =
                error("unregistered submodule must never be fetched")

            override fun stash(workDir: File, message: String): GitResult =
                error("unregistered submodule must never be stashed")

            override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult =
                error("unregistered submodule must never be isolated")
        }
        val c = context().copy(
            git = noTouchGit,
            preset = Preset("moved", "main", mapOf("OldSub" to "dev")),
        )

        val execution = SubmoduleTreeStep().run(
            c,
            SwitchState().withSuccessfulCheckout("."),
        )

        assertTrue(execution.state.isSkipped("OldSub"))
        assertTrue(execution.state.stashesSnapshot().isEmpty())
    }

    @Test
    fun `submodule remote added after checkpoint blocks checkout`() {
        projectRoot.resolve("SubA").toFile().mkdirs()
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
                SubmoduleRegistration("SubA", "SubA", ".", url = "replacement-url"),
            )

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("replacement", "main", mapOf("SubA" to "dev")),
            checkpoint = mapOf("SubA" to CheckpointEntry("before", "main", declaredUrl = null)),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertEquals(
            listOf("SubA" to OperationIssueCode.REPOSITORY_REMOTE_CHANGED),
            (execution.result as StepResult.Partial).issues.map { it.repositoryPath to it.code },
        )
        assertEquals(0, checkoutCalls)
    }

    @Test
    fun `unregistered at checkpoint target registered by the preset is not blocked as remote change`() {
        projectRoot.resolve("SubA").toFile().mkdirs()
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
                SubmoduleRegistration("SubA", "SubA", ".", url = "appears-url"),
            )

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("restore", "main", mapOf("SubA" to "dev")),
            checkpoint = mapOf(
                "SubA" to CheckpointEntry("before", "main", declaredUrl = null, registeredAtCheckpoint = false),
            ),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertTrue(
            "a target unregistered at checkpoint time must not be skipped as a remote change",
            execution.result is StepResult.Success,
        )
        assertEquals(1, checkoutCalls)
    }

    @Test
    fun `submodule with unchanged declared URL is not blocked`() {
        projectRoot.resolve("SubA").toFile().mkdirs()
        var checkoutCalls = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
                SubmoduleRegistration("SubA", "SubA", ".", url = "upstream"),
            )

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("replacement", "main", mapOf("SubA" to "dev")),
            checkpoint = mapOf(
                "SubA" to CheckpointEntry("before", "main", declaredUrl = "upstream"),
            ),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertTrue("unchanged declared URL must not trigger a remote-change skip", execution.result is StepResult.Success)
        assertEquals(1, checkoutCalls)
    }

    @Test
    fun `nested registration refreshes after parent checkout`() {
        projectRoot.resolve("Parent/Nested").toFile().mkdirs()
        val checkedOut = mutableListOf<String>()
        var registrationReads = 0
        val validatingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean = true

            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> {
                registrationReads++
                return if (registrationReads == 1) {
                    listOf(SubmoduleRegistration("Parent", "Parent", "."))
                } else {
                    listOf(
                        SubmoduleRegistration("Parent", "Parent", "."),
                        SubmoduleRegistration("Parent/Nested", "Nested", "Parent"),
                    )
                }
            }

            override fun repositoryIdentity(workDir: File): RepositoryIdentity {
                val root = projectRoot.toFile().canonicalFile
                val relative = workDir.canonicalFile.relativeTo(root).invariantSeparatorsPath
                val gitDirectory = when (relative) {
                    "" -> File(root, ".git")
                    "Parent" -> File(root, ".git/modules/Parent")
                    else -> File(root, ".git/modules/Parent/modules/Nested")
                }
                val superproject = when (relative) {
                    "" -> null
                    "Parent" -> root.path
                    else -> File(root, "Parent").path
                }
                return RepositoryIdentity(gitDirectory.absolutePath, superproject)
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkedOut += workDir.relativeTo(projectRoot.toFile()).invariantSeparatorsPath
                return GitResult("checkout", 0, "", "")
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset(
                "nested",
                "main",
                linkedMapOf("Parent/Nested" to "dev", "Parent" to "dev"),
            ),
        )

        val execution = SubmoduleTreeStep().run(
            c,
            SwitchState().withSuccessfulCheckout("."),
        )

        assertTrue(
            "Expected nested checkout success, got ${execution.result}",
            execution.result is StepResult.Success,
        )
        assertEquals(listOf("Parent", "Parent/Nested"), checkedOut)
    }

    @Test
    fun `nested target initializes from its registered parent when parent is not in preset`() {
        val parent = projectRoot.resolve("Parent").toFile().also(File::mkdirs)
        var initRoot: File? = null
        var initPath: String? = null
        val validatingGit = object : GitClient by fakeGit {
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> =
                listOf(SubmoduleRegistration("Parent/Nested", "Nested", "Parent"))

            override fun submoduleInitPath(gitRoot: File, path: String): GitResult {
                initRoot = gitRoot
                initPath = path
                File(gitRoot, path).mkdirs()
                return GitResult("init", 0, "", "")
            }

            override fun isGitRepo(workDir: File): Boolean = workDir.exists() && workDir.name == "Nested"

            override fun repositoryIdentity(workDir: File): RepositoryIdentity {
                val root = projectRoot.toFile().canonicalFile
                return when (workDir.name) {
                    "Parent" -> RepositoryIdentity(File(root, ".git/modules/Parent").path, root.path)
                    "Nested" -> RepositoryIdentity(
                        File(root, ".git/modules/Parent/modules/Nested").path,
                        File(root, "Parent").path,
                    )
                    else -> RepositoryIdentity(File(root, ".git").path, null)
                }
            }
        }
        val c = context().copy(
            git = validatingGit,
            preset = Preset("nested", "main", mapOf("Parent/Nested" to "dev")),
        )

        val execution = SubmoduleTreeStep().run(c, SwitchState().withSuccessfulCheckout("."))

        assertTrue("Expected nested init success, got ${execution.result}", execution.result is StepResult.Success)
        assertEquals(parent.canonicalFile, initRoot?.canonicalFile)
        assertEquals("Nested", initPath)
    }

}
