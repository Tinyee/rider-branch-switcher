
package com.submodule.branchswitcher.switch
import com.submodule.branchswitcher.executeTest
import com.submodule.branchswitcher.executeResultTest

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.RepoTarget
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException

class SwitchExecutorTest {

    private val log = mutableListOf<String>()

    // Default fake: clean repos, main branch exists, everything succeeds
    private val fakeGit = object : GitClient {
        override fun currentBranch(workDir: File): String? = "main"
        override fun isDirty(workDir: File): Boolean = false
        override fun dirtyFileCount(workDir: File): Int = 0
        override fun stash(workDir: File, message: String): GitResult = GitResult("stash", 0, "", "")
        override fun stashTopOid(workDir: File): String = "stash-oid"
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
        override fun stashApply(workDir: File, oid: String): GitResult = GitResult("pop", 0, "", "")
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
        override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
        override fun repositoryIdentity(workDir: File): RepositoryIdentity {
            val root = projectRoot.toFile().canonicalFile
            val directory = if (workDir.canonicalFile == root) {
                File(root, ".git")
            } else {
                File(root, ".git/modules/${workDir.name}")
            }
            return RepositoryIdentity(directory.absolutePath, root.takeIf { workDir.canonicalFile != root }?.path)
        }
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = listOf(
            SubmoduleRegistration("SubA", "SubA", "."),
            SubmoduleRegistration("SubB", "SubB", "."),
        )
        override fun resetHard(workDir: File, revision: String): GitResult = GitResult("reset", 0, "", "")
        override fun cancel() = Unit
        override fun localBranchProbe(workDir: File, branch: String): Boolean = localBranchExists(workDir, branch)
        override fun dirtyProbe(workDir: File): Boolean = isDirty(workDir)
    }

    private val projectRoot = java.nio.file.Files.createTempDirectory("test-executor")
    private val preset = Preset("test", "dev", emptyMap())

    private fun recovery(git: GitClient = fakeGit) =
        SwitchRecoveryExecutor(projectRoot, createStringAppender { log += it }, git)

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

    @Test
    fun `checkpoint failure blocks switch before checkout`() {
        var checkoutCalls = 0
        val missingHeadGit = object : GitClient by fakeGit {
            override fun revParseHead(workDir: File): String? = null
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, missingHeadGit)

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        assertFalse(result.ok)
        assertEquals("Checkpoint failure must prevent checkout", 0, checkoutCalls)
        assertNull("Incomplete checkpoints must not be retained", result.checkpoint)
        assertTrue(log.any { it.contains("[checkpoint]") && it.contains("unable to read HEAD") })
    }

    @Test
    fun `stale index lock fails the switch before any mutation`() {
        var checkoutCalls = 0
        val lockedGit = object : GitClient by fakeGit {
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, lockedGit)

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        val issue = result.issues.single()
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, issue.code)
        assertTrue(issue.diagnostic.orEmpty().contains("/repo/.git/index.lock"))
        assertEquals("Index lock must block the switch before checkout", 0, checkoutCalls)
        assertTrue(log.any { it.contains("index.lock") })
    }

    @Test
    fun `index lock created after preflight blocks the next write`() {
        var lockChecks = 0
        var checkoutCalls = 0
        val racingGit = object : GitClient by fakeGit {
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun indexLockFile(workDir: File): String? {
                lockChecks++
                return if (lockChecks == 1) null else "/repo/.git/index.lock"
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, racingGit)

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        assertEquals(0, checkoutCalls)
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, result.issues.single().code)
        assertTrue(lockChecks >= 2)
    }

    @Test
    fun `lock preflight reuses checkpoint repository discovery`() {
        var isGitRepoCalls = 0
        var indexLockFileCalls = 0
        val countingGit = object : GitClient by fakeGit {
            override fun isGitRepo(workDir: File): Boolean {
                isGitRepoCalls++
                return true
            }

            override fun indexLockFile(workDir: File): String? {
                indexLockFileCalls++
                return null
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            countingGit,
            steps = emptyList(),
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue(result.ok)
        assertEquals("checkpoint should provide the existing-repository fact", 1, isGitRepoCalls)
        assertEquals("known git directories should cover the common no-lock path", 0, indexLockFileCalls)
    }

    @Test
    fun `lock preflight query failure returns a structured failed result`() {
        val failingGit = object : GitClient by fakeGit {
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun indexLockFile(workDir: File): String? {
                throw GitQueryException(
                    GitResult(
                        "git rev-parse --git-path index.lock",
                        -1,
                        "",
                        "process capacity unavailable after 60s",
                    ),
                )
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            failingGit,
            steps = emptyList(),
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        val issue = result.issues.single()
        assertEquals(OperationStage.CHECKPOINT, issue.stage)
        assertEquals(OperationIssueCode.GIT_QUERY_FAILED, issue.code)
        assertTrue(issue.diagnostic.orEmpty().contains(GitFailureKind.PROCESS_CAPACITY.name))
    }

    @Test
    fun `lock preflight cancellation is rethrown not converted to a failure`() {
        val cancelledGit = object : GitClient by fakeGit {
            // Null repositoryId forces findBlockingIndexLocks to the git.indexLockFile
            // query (a real .git directory would take the File-stat fast path instead).
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun indexLockFile(workDir: File): String? {
                throw GitQueryException(
                    GitResult("git rev-parse --git-path index.lock", -1, "", "cancelled"),
                )
            }
        }
        val classifier = CancellationClassifier { e ->
            e is GitQueryException &&
                e.result.failureKind in setOf(GitFailureKind.CANCELLED, GitFailureKind.INTERRUPTED)
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            cancelledGit,
            cancellationClassifier = classifier,
            steps = emptyList(),
        )

        var thrown: Throwable? = null
        try {
            executor.executeResultTest(
                preset,
                SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("a cancelled lock preflight must propagate as cancellation", thrown is GitQueryException)
        assertEquals(GitFailureKind.CANCELLED, (thrown as GitQueryException).result.failureKind)
    }

    @Test
    fun `checkpoint query failure returns a structured failed result`() {
        val failingGit = object : GitClient by fakeGit {
            override fun revParseHead(workDir: File): String? {
                throw GitQueryException(
                    GitResult(
                        "git rev-parse HEAD",
                        -1,
                        "",
                        "process capacity unavailable after 60s",
                    ),
                )
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            failingGit,
            steps = emptyList(),
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        val issue = result.issues.single()
        assertEquals(OperationStage.CHECKPOINT, issue.stage)
        assertEquals(OperationIssueCode.GIT_QUERY_FAILED, issue.code)
        assertTrue(issue.diagnostic.orEmpty().contains(GitFailureKind.PROCESS_CAPACITY.name))
        assertNull("No checkpoint must be retained after a query failure", result.checkpoint)
    }

    @Test
    fun `checkpoint query cancellation is rethrown not downgraded to a failure`() {
        val cancelledGit = object : GitClient by fakeGit {
            override fun revParseHead(workDir: File): String? {
                throw GitQueryException(
                    GitResult("git rev-parse HEAD", -1, "", "cancelled"),
                )
            }
        }
        val classifier = CancellationClassifier { e ->
            e is GitQueryException &&
                e.result.failureKind in setOf(GitFailureKind.CANCELLED, GitFailureKind.INTERRUPTED)
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            cancelledGit,
            cancellationClassifier = classifier,
            steps = emptyList(),
        )

        var thrown: Throwable? = null
        try {
            executor.executeResultTest(
                preset,
                SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
            )
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("a cancelled checkpoint query must propagate as cancellation", thrown is GitQueryException)
        assertEquals(GitFailureKind.CANCELLED, (thrown as GitQueryException).result.failureKind)
    }

    @Test
    fun `failed main checkout prevents every downstream repository mutation`() {
        val submodule = projectRoot.resolve("SubA").toFile()
        initGitRepo(submodule)
        val downstreamMutations = mutableListOf<String>()
        val failingGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult {
                if (workDir == submodule) downstreamMutations += "submodule fetch"
                return GitResult("fetch", 0, "", "")
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (workDir == projectRoot.toFile()) {
                    return GitResult("checkout", 1, "", "checkout failed")
                }
                downstreamMutations += "submodule checkout"
                return GitResult("checkout", 0, "", "")
            }

            override fun pullFf(workDir: File, branch: String): GitResult {
                downstreamMutations += if (workDir == submodule) "submodule pull" else "main pull"
                return GitResult("pull", 0, "", "")
            }

            override fun submoduleSync(gitRoot: File): GitResult {
                downstreamMutations += "submodule sync"
                return GitResult("sync", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, failingGit)

        val result = executor.executeResultTest(
            Preset("test", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true),
        )

        assertFalse(result.ok)
        assertTrue(result.state.isSkipped("SubA"))
        assertEquals(emptyList<String>(), downstreamMutations)
    }

    @Test
    fun `failed submodule sync prevents every submodule mutation`() {
        val submodule = projectRoot.resolve("SubA").toFile()
        initGitRepo(submodule)
        val submoduleMutations = mutableListOf<String>()
        val failingGit = object : GitClient by fakeGit {
            override fun fetch(workDir: File): GitResult {
                if (workDir == submodule) submoduleMutations += "fetch"
                return GitResult("fetch", 0, "", "")
            }

            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                if (workDir == submodule) submoduleMutations += "checkout"
                return GitResult("checkout", 0, "", "")
            }

            override fun pullFf(workDir: File, branch: String): GitResult {
                if (workDir == submodule) submoduleMutations += "pull"
                return GitResult("pull", 0, "", "")
            }

            override fun submoduleSync(gitRoot: File): GitResult =
                GitResult("sync", 1, "", "sync failed")
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, failingGit)

        val result = executor.executeResultTest(
            Preset("test", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true),
        )

        assertFalse(result.ok)
        assertTrue(result.state.isSkipped("SubA"))
        assertEquals(emptyList<String>(), submoduleMutations)
    }

    // ---- Rollback ----

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
        assertEquals(setOf("SubA"), outcome.stashRestore.state.retainedStashBackupsSnapshot())
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
            override val stage = OperationStage.CHECKOUT
            override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
                executed += name
                cancelled = true
                return StepExecution(StepResult.Success, state)
            }
        }
        val second = object : SwitchStep {
            override val name = "second"
            override val stage = OperationStage.CHECKOUT
            override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
                executed += name
                return StepExecution(StepResult.Success, state)
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            trackingGit,
            cancelled = { cancelled },
            steps = listOf(first, second),
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertFalse(result.ok)
        assertTrue(result.cancelled)
        assertEquals(listOf("first"), executed)
        assertEquals(1, cancelCalls)
        assertTrue(log.any { it.contains("[cancelled] before step: second") })
    }

    @Test
    fun `cancellation inside dirty step retains stash state for recovery`() {
        initGitRepo(File(projectRoot.toFile(), "SubA"))
        var checks = 0
        var cancelCalls = 0
        val cancellation = object : CancellationHandle {
            override fun checkCanceled() {
                checks++
                if (checks == 3) throw CancellationException("cancel after first target")
            }

            override val isCanceled = false
        }
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun cancel() {
                cancelCalls++
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            cancellationHandle = cancellation,
            steps = listOf(DirtyHandlingStep()),
        )

        val result = executor.executeResultTest(
            Preset("sub", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue(result.cancelled)
        assertEquals(setOf("."), result.state.stashesSnapshot().keys)
        assertEquals(1, cancelCalls)
    }

    @Test
    fun `failed step restores stashes created by an earlier step`() {
        var stashApplyCalls = 0
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stashApply(workDir: File, oid: String): GitResult {
                stashApplyCalls++
                return GitResult("stash pop", 0, "", "")
            }
        }
        val failingStep = object : SwitchStep {
            override val name = "failing step"
            override val stage = OperationStage.CHECKOUT
            override fun execute(context: SwitchContext, state: SwitchState): StepExecution =
                error("checkout query failed")
        }
        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            steps = listOf(DirtyHandlingStep(), failingStep),
        ).executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        assertEquals(1, stashApplyCalls)
        assertTrue("failed switches must not leave WIP hidden in stash", result.state.stashesSnapshot().isEmpty())
        assertEquals(setOf("."), result.state.retainedStashBackupsSnapshot())
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
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            steps = listOf(DirtyHandlingStep()),
        )

        val result = executor.executeResultTest(
            Preset("sub", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        assertNotNull(result.checkpoint)
        assertTrue("failed switch should restore the stash created before the query failure", result.state.stashesSnapshot().isEmpty())
        assertEquals(setOf("."), result.state.retainedStashBackupsSnapshot())
        val issue = result.issues.single()
        assertEquals(OperationStage.DIRTY_HANDLING, issue.stage)
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

    // ---- Shared utilities ----

    @Test
    fun `shortLabel extracts basename and strips display suffix`() {
        assertEquals("SubA", shortLabel("lib/SubA"))
        assertEquals("main", shortLabel("main"))
        assertEquals("repo", shortLabel("a/b/c/repo"))
        assertEquals("SubA", shortLabel("lib/SubA~"))
        assertEquals("x", shortLabel("x~"))
    }
}
