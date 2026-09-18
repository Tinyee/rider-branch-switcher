package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.DeriveGitClient
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeriveBranchExecutorTest {

    private val projectRoot = java.nio.file.Files.createTempDirectory("test-derive")

    /**
     * Configurable fake for the derive Git surface. Defaults to a healthy main-repo repo
     * on branch "dev" at SHA "abc123" that would succeed end to end; individual tests
     * override just the probes they need to block.
     */
    private open inner class FakeDeriveGit : DeriveGitClient {
        var onMain: String? = "dev"
        var headSha: String? = "abc123"
        var repoExists: Boolean = true
        var dirty: Boolean = false
        var branchExists: Boolean = false
        var lock: String? = null
        var checkoutResult: GitResult = GitResult("checkout", 0, "", "")
        var checkoutExistingResult: GitResult = GitResult("checkout", 0, "", "")
        var deleteResult: GitResult = GitResult("branch", 0, "", "")
        var registrations: List<SubmoduleRegistration> = emptyList()
        var checkoutCalls = 0
        var checkoutExistingCalls = 0
        var deleteCalls = 0
        var failIndexLockProbe: Exception? = null

        /** Identity reported per workdir; [projectRoot] itself gets [mainIdentity] when set, else null. */
        var mainIdentity: RepositoryIdentity? = null
        var submoduleIdentity: RepositoryIdentity? = null

        override fun currentBranch(workDir: File): String? = onMain
        override fun revParseHead(workDir: File): String? = headSha
        override fun localBranchExists(workDir: File, branch: String): Boolean = branchExists
        override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
        override fun isDirty(workDir: File): Boolean = dirty
        override fun checkoutNewBranch(workDir: File, branch: String): GitResult {
            checkoutCalls++
            return checkoutResult
        }
        override fun checkoutExisting(workDir: File, branch: String): GitResult {
            checkoutExistingCalls++
            return checkoutExistingResult
        }
        override fun deleteBranch(workDir: File, branch: String): GitResult {
            deleteCalls++
            return deleteResult
        }
        override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = registrations
        override fun repositoryIdentity(workDir: File): RepositoryIdentity? = when {
            workDir == projectRoot.toFile() -> mainIdentity
            else -> submoduleIdentity
        }
        override fun isGitRepo(workDir: File): Boolean = repoExists
        override fun indexLockFile(workDir: File): String? {
            failIndexLockProbe?.let { throw it }
            return lock
        }
    }

    private fun executor(
        git: DeriveGitClient,
        requireClean: Boolean = true,
        isCancelled: () -> Boolean = { false },
    ): DeriveBranchExecutor {
        val operationControl = object : OperationControl {
            override fun checkCancelled() {
                if (isCancelled()) throw OperationCancelledException()
            }
            override val isCanceled: Boolean get() = isCancelled()
        }
        return DeriveBranchExecutor(
            projectRoot,
            createStringAppender { _ -> },
            git,
            operationControl,
            requireClean,
        )
    }

    @Test
    fun `derive creates the branch and records the checkpoint on the healthy path`() {
        val git = FakeDeriveGit()
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SUCCEEDED, outcome.status)
        assertNull("a success carries no issue", outcome.issue)
        assertEquals(1, git.checkoutCalls)
        // The checkpoint captured the HEAD SHA and branch before the write.
        assertEquals(1, result.checkpoint.size)
        assertEquals("abc123", result.checkpoint.getValue(".").sha)
        assertEquals("dev", result.checkpoint.getValue(".").branch)
        assertTrue(!result.cancelled)
    }

    @Test
    fun `derive preflight blocks when the branch already exists`() {
        val git = FakeDeriveGit().apply { branchExists = true }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.BRANCH_EXISTS, outcome.status)
        assertEquals(OperationIssueCode.BRANCH_ALREADY_EXISTS, outcome.issue?.code)
        assertEquals(0, git.checkoutCalls)
        assertTrue("blocked preflight must not create branches", result.checkpoint.isEmpty())
    }

    @Test
    fun `derive preflight blocks when the current branch differs from the preset`() {
        val git = FakeDeriveGit().apply { onMain = "main" }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.BRANCH_MISMATCH, outcome.status)
        assertEquals(OperationIssueCode.BRANCH_MISMATCH, outcome.issue?.code)
        assertTrue(outcome.issue?.diagnostic.orEmpty().contains("expected=dev, actual=main"))
        assertEquals(0, git.checkoutCalls)
    }

    @Test
    fun `derive preflight blocks on a detached HEAD`() {
        // currentBranch returns null on a detached HEAD; the preflight treats it as a mismatch.
        val git = FakeDeriveGit().apply { onMain = null }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.BRANCH_MISMATCH, outcome.status)
        assertEquals(OperationIssueCode.BRANCH_MISMATCH, outcome.issue?.code)
    }

    @Test
    fun `derive preflight blocks a dirty working tree`() {
        val git = FakeDeriveGit().apply { dirty = true }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.DIRTY, outcome.status)
        assertEquals(OperationIssueCode.WORKTREE_DIRTY, outcome.issue?.code)
        assertEquals(0, git.checkoutCalls)
    }

    @Test
    fun `derive preflight blocks an unregistered submodule`() {
        // A submodule in the preset that the current .gitmodules graph does not register.
        val git = FakeDeriveGit()
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", mapOf("SubA" to "dev")), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SKIPPED, outcome.status)
        assertEquals(OperationIssueCode.SUBMODULE_NOT_REGISTERED, outcome.issue?.code)
        assertEquals(0, git.checkoutCalls)
    }

    @Test
    fun `derive preflight admits a registered submodule and derives it`() {
        // Two-sided gate: the mirror of the unregistered case above. A preset submodule
        // that IS in the .gitmodules graph (with an associated worktree) must pass the
        // registration gate, checkpoint, and reach branch creation. Without this, a
        // regression that blocks deriving on every registered submodule would pass green
        // (the fake could only ever produce the empty-topology side).
        val subDir = java.io.File(projectRoot.toFile(), "SubA").apply { mkdirs() }
        val git = FakeDeriveGit().apply {
            registrations = listOf(
                SubmoduleRegistration(path = "SubA", sectionName = "SubA", parentPath = ".", url = "https://example.com/a.git"),
            )
            mainIdentity = RepositoryIdentity(
                gitDirectory = "${projectRoot.toFile().path}/.git",
                superprojectRoot = projectRoot.toString(),
            )
            submoduleIdentity = RepositoryIdentity(
                gitDirectory = "${projectRoot.toFile().path}/.git/modules/SubA",
                superprojectRoot = projectRoot.toString(),
            )
        }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", mapOf("SubA" to "dev")), "feature-x")

        // Both the main repo and the registered submodule pass preflight and are derived.
        val outcomes = result.outcomes
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it.status == DeriveRepositoryStatus.SUCCEEDED })
        assertTrue(outcomes.all { it.issue == null })
        assertEquals(2, git.checkoutCalls)
        subDir.deleteRecursively()
    }

    @Test
    fun `derive preflight blocks a target that is not a git repo`() {
        val git = FakeDeriveGit().apply { repoExists = false }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SKIPPED, outcome.status)
        assertEquals(OperationIssueCode.REPOSITORY_MISSING, outcome.issue?.code)
    }

    @Test
    fun `derive checkpoint fails and blocks when the HEAD cannot be resolved`() {
        // resolveHeadAndBranch returns null when neither headAndBranch nor revParseHead yields a SHA.
        val git = FakeDeriveGit().apply { headSha = null }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.CHECKPOINT_FAILED, outcome.status)
        assertEquals(OperationIssueCode.DERIVE_CHECKPOINT_FAILED, outcome.issue?.code)
        assertEquals(0, git.checkoutCalls)
        assertTrue("a checkpoint failure must not create the branch", result.checkpoint.isEmpty())
    }

    @Test
    fun `derive reports a branch-creation failure as FAILED with BRANCH_CREATE_FAILED`() {
        val git = FakeDeriveGit().apply {
            checkoutResult = GitResult("checkout", 1, "", "fatal: cannot create branch")
        }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.FAILED, outcome.status)
        assertEquals(OperationIssueCode.BRANCH_CREATE_FAILED, outcome.issue?.code)
        assertEquals(OperationStage.DERIVE, outcome.issue?.stage)
        assertEquals(1, git.checkoutCalls)
    }

    @Test
    fun `derive with requireClean false skips the dirty gate`() {
        val git = FakeDeriveGit().apply { dirty = true }
        val executor = executor(git, requireClean = false)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SUCCEEDED, outcome.status)
        assertEquals(1, git.checkoutCalls)
    }

    @Test
    fun `derive stops between repositories when the operation is cancelled`() {
        val git = FakeDeriveGit()
        // Cancelled from the first target: the preflight loop breaks before inspecting it,
        // so nothing is eligible, nothing is created, and the result is marked cancelled.
        val executor = executor(git, isCancelled = { true })

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        assertTrue(result.cancelled)
        assertEquals(0, git.checkoutCalls)
        assertTrue("a cancelled derive must not record a checkpoint", result.checkpoint.isEmpty())
    }

    @Test
    fun `derive preflight failure of the lock probe is PREFLIGHT_FAILED not a block`() {
        val git = FakeDeriveGit().apply {
            failIndexLockProbe = GitQueryException(
                GitResult("git rev-parse --git-path index.lock", -1, "", "process capacity unavailable after 60s"),
            )
        }
        val executor = executor(git)

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.PREFLIGHT_FAILED, outcome.status)
        assertEquals(OperationIssueCode.PREFLIGHT_FAILED, outcome.issue?.code)
    }

    @Test
    fun `derive preflight blocks a repository with a stale index lock`() {
        val log = mutableListOf<String>()
        val lockedGit = object : DeriveGitClient {
            override fun currentBranch(workDir: File): String? = "main"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun isDirty(workDir: File): Boolean = false
            override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun isGitRepo(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? = "/repo/.git/index.lock"
        }
        val executor = DeriveBranchExecutor(
            projectRoot,
            createStringAppender { log += it },
            lockedGit,
        )

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        assertEquals(1, result.outcomes.size)
        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.SKIPPED, outcome.status)
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, outcome.issue?.code)
        assertTrue(outcome.issue?.diagnostic.orEmpty().contains("/repo/.git/index.lock"))
        assertEquals(emptyMap<String, DeriveCheckpointEntry>(), result.checkpoint)
        assertTrue(log.any { it.contains("stale index.lock blocks branch creation") })
    }

    @Test
    fun `rollback treats an already-deleted branch as rolled back`() {
        val log = mutableListOf<String>()
        val git = object : DeriveGitClient {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun isDirty(workDir: File): Boolean = false
            override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun deleteBranch(workDir: File, branch: String): GitResult =
                GitResult("branch", 1, "", "fatal: branch 'feature-x' not found")
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun isGitRepo(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? = null
        }
        val executor = DeriveBranchExecutor(projectRoot, createStringAppender { log += it }, git)
        val deriveResult = DeriveResult(
            outcomes = listOf(DeriveRepositoryOutcome("SubA", DeriveRepositoryStatus.SUCCEEDED, null)),
            checkpoint = mapOf("SubA" to DeriveCheckpointEntry("base-sha", "dev")),
        )

        val rollback = executor.rollbackSucceeded(deriveResult, "feature-x")

        assertTrue("an already-deleted branch is not a pending path", rollback.pendingPaths.isEmpty())
    }

    @Test
    fun `index lock created after preflight blocks branch creation`() {
        val log = mutableListOf<String>()
        var lockChecks = 0
        val lateLockGit = object : DeriveGitClient {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun isDirty(workDir: File): Boolean = false
            override fun checkoutNewBranch(workDir: File, branch: String): GitResult {
                error("checkoutNewBranch must not run behind a stale index lock")
            }
            override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun isGitRepo(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? {
                lockChecks++
                return if (lockChecks == 1) null else "/repo/.git/index.lock"
            }
        }
        val executor = DeriveBranchExecutor(
            projectRoot,
            createStringAppender { log += it },
            lateLockGit,
        )

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.FAILED, outcome.status)
        assertEquals(OperationIssueCode.INDEX_LOCK_BLOCKING, outcome.issue?.code)
        assertEquals(OperationStage.DERIVE, outcome.issue?.stage)
        assertEquals("/repo/.git/index.lock", outcome.issue?.lockPath)
        assertTrue(log.any { it.contains("stale index.lock blocks branch creation") })
    }

    @Test
    fun `index lock probe failure is a preflight failure not a block`() {
        val log = mutableListOf<String>()
        val failingLockGit = object : DeriveGitClient {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun revParseHead(workDir: File): String? = "abc123"
            override fun localBranchExists(workDir: File, branch: String): Boolean = false
            override fun remoteBranchExists(workDir: File, branch: String): Boolean = false
            override fun isDirty(workDir: File): Boolean = false
            override fun checkoutNewBranch(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun checkoutExisting(workDir: File, branch: String): GitResult = GitResult("checkout", 0, "", "")
            override fun deleteBranch(workDir: File, branch: String): GitResult = GitResult("branch", 0, "", "")
            override fun registeredSubmodules(gitRoot: File): List<SubmoduleRegistration> = emptyList()
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? = null
            override fun isGitRepo(workDir: File): Boolean = true
            override fun indexLockFile(workDir: File): String? = throw GitQueryException(
                GitResult(
                    "git rev-parse --git-path index.lock",
                    -1,
                    "",
                    "process capacity unavailable after 60s",
                ),
            )
        }
        val executor = DeriveBranchExecutor(
            projectRoot,
            createStringAppender { log += it },
            failingLockGit,
        )

        val result = executor.execute(Preset("test", "dev", emptyMap()), "feature-x")

        val outcome = result.outcomes.single()
        assertEquals(DeriveRepositoryStatus.PREFLIGHT_FAILED, outcome.status)
        assertEquals(OperationIssueCode.PREFLIGHT_FAILED, outcome.issue?.code)
        assertTrue(log.any { it.contains("index lock probe failed") })
    }

    private fun rollbackFixture(
        checkpointId: String? = null,
    ): DeriveResult = DeriveResult(
        outcomes = listOf(DeriveRepositoryOutcome(".", DeriveRepositoryStatus.SUCCEEDED, null)),
        checkpoint = mapOf("." to DeriveCheckpointEntry("base-sha", "dev", checkpointId)),
    )

    @Test
    fun `rollback restores the original branch and deletes the derived branch`() {
        val git = FakeDeriveGit()
        val executor = executor(git)

        val rollback = executor.rollbackSucceeded(rollbackFixture(), "feature-x")

        assertTrue("a clean rollback leaves nothing pending", rollback.pendingPaths.isEmpty())
        assertEquals(1, git.checkoutExistingCalls)
        assertEquals(1, git.deleteCalls)
    }

    @Test
    fun `rollback defers a path with no checkpoint entry`() {
        val git = FakeDeriveGit()
        val executor = executor(git)
        val result = DeriveResult(
            outcomes = listOf(DeriveRepositoryOutcome("SubA", DeriveRepositoryStatus.SUCCEEDED, null)),
            checkpoint = emptyMap(),
        )

        val rollback = executor.rollbackSucceeded(result, "feature-x")

        assertEquals(listOf("SubA"), rollback.pendingPaths)
        assertEquals(0, git.checkoutExistingCalls)
    }

    @Test
    fun `rollback defers a path blocked by a stale index lock`() {
        val git = FakeDeriveGit().apply { lock = "/repo/.git/index.lock" }
        val executor = executor(git)

        val rollback = executor.rollbackSucceeded(rollbackFixture(), "feature-x")

        assertEquals(listOf("."), rollback.pendingPaths)
        assertEquals(0, git.checkoutExistingCalls)
        assertEquals(0, git.deleteCalls)
    }

    @Test
    fun `rollback defers a path whose checkout rollback fails`() {
        val git = FakeDeriveGit().apply {
            checkoutExistingResult = GitResult("checkout", 1, "", "fatal: cannot checkout")
        }
        val executor = executor(git)

        val rollback = executor.rollbackSucceeded(rollbackFixture(), "feature-x")

        assertEquals(listOf("."), rollback.pendingPaths)
        assertEquals(1, git.checkoutExistingCalls)
        assertEquals(0, git.deleteCalls)
    }

    @Test
    fun `rollback defers a path when the repository identity changed`() {
        val git = FakeDeriveGit().apply {
            submoduleIdentity = RepositoryIdentity(gitDirectory = "/other/.git", superprojectRoot = null)
        }
        val executor = executor(git)
        // The checkpoint recorded a different repository id than the current one.
        val fixture = rollbackFixture(checkpointId = "/original/.git")

        val rollback = executor.rollbackSucceeded(fixture, "feature-x")

        assertEquals(listOf("."), rollback.pendingPaths)
        assertEquals(0, git.checkoutExistingCalls)
    }

    @Test
    fun `rollback defers a path when the derived branch cannot be deleted`() {
        val git = FakeDeriveGit().apply {
            deleteResult = GitResult("branch", 1, "", "fatal: branch 'feature-x' not fully merged")
            branchExists = true
        }
        val executor = executor(git)

        val rollback = executor.rollbackSucceeded(rollbackFixture(), "feature-x")

        assertEquals(listOf("."), rollback.pendingPaths)
        assertEquals(1, git.checkoutExistingCalls)
        assertEquals(1, git.deleteCalls)
    }

    @Test
    fun `rollback ignores selected paths that were not succeeded`() {
        val git = FakeDeriveGit()
        val executor = executor(git)
        val result = DeriveResult(
            outcomes = listOf(
                DeriveRepositoryOutcome(".", DeriveRepositoryStatus.SUCCEEDED, null),
                DeriveRepositoryOutcome("SubB", DeriveRepositoryStatus.FAILED, null),
            ),
            checkpoint = mapOf(
                "." to DeriveCheckpointEntry("base-sha", "dev"),
                "SubB" to DeriveCheckpointEntry("sha-b", "dev"),
            ),
        )

        // Request rollback for the succeeded main plus the never-succeeded SubB; only
        // the succeeded path may be rolled back.
        val rollback = executor.rollbackSucceeded(result, "feature-x", selectedPaths = listOf(".", "SubB"))

        assertTrue(rollback.pendingPaths.isEmpty())
        assertEquals(1, git.checkoutExistingCalls)
    }

    @Test
    fun `rollback defers remaining paths when cancelled mid-way`() {
        val git = FakeDeriveGit()
        val executor = executor(git, isCancelled = { true })

        val rollback = executor.rollbackSucceeded(rollbackFixture(), "feature-x")

        // Cancelled before the first path; all succeeded paths are deferred.
        assertEquals(listOf("."), rollback.pendingPaths)
        assertEquals(0, git.checkoutExistingCalls)
    }
}
