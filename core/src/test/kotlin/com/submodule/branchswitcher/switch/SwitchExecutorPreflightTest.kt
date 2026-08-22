package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Preflight, lock, checkpoint, and downstream-containment behavior of [SwitchExecutor]. */
class SwitchExecutorPreflightTest : SwitchExecutorTestBase() {

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
    fun `already-at-target clean switch is a no-op without side effects`() {
        var stashCalls = 0
        var checkoutCalls = 0
        var pullCalls = 0
        val noopGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun stash(workDir: File, message: String): GitResult {
                stashCalls++
                return GitResult("stash", 0, "", "")
            }
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult("checkout", 0, "", "")
            }
            override fun pullFf(workDir: File, branch: String): GitResult {
                pullCalls++
                return GitResult("pull", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noopGit)

        val result = executor.executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue("already-at-target must succeed", result.ok)
        assertEquals("a no-op switch must not re-stash", 0, stashCalls)
        assertEquals("a no-op switch must not re-checkout", 0, checkoutCalls)
        assertEquals("a no-op switch must not re-pull", 0, pullCalls)
    }

    @Test
    fun `pull enabled prevents the already-at-target short circuit`() {
        var pullCalls = 0
        val noopGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun pullFf(workDir: File, branch: String): GitResult {
                pullCalls++
                return GitResult("pull", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noopGit)

        val result = executor.executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = false),
        )

        assertTrue(result.ok)
        assertEquals("pull is part of the switch and must still run", 1, pullCalls)
    }

    @Test
    fun `fetch-first prevents the already-at-target short circuit even without pull`() {
        var fetchCalls = 0
        val noopGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
            override fun fetch(workDir: File): GitResult {
                fetchCalls++
                return GitResult("fetch", 0, "", "")
            }
        }
        val executor = SwitchExecutor(projectRoot, createStringAppender { log += it }, noopGit)

        val result = executor.executeResultTest(
            Preset("test", "dev", emptyMap()),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true),
        )

        assertTrue(result.ok)
        assertEquals("a fetch-first switch must still fetch even when already at target", 1, fetchCalls)
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
        // The checkpoint already records "." as an existing repository with its git directory,
        // so the lock preflight stats the index.lock path directly and never re-probes git.
        val checkpoint = mapOf(
            "." to CheckpointEntry(
                sha = "abc123",
                branch = "main",
                repositoryId = File(projectRoot.toFile(), ".git").canonicalPath,
            ),
        )

        val blocks = findBlockingIndexLocks(projectRoot, countingGit, listOf("."), checkpoint)

        assertEquals(emptyList<IndexLockBlock>(), blocks)
        assertEquals("a recorded checkpoint must make the git-existence probe unnecessary", 0, isGitRepoCalls)
        assertEquals("a known git directory must cover the common no-lock path", 0, indexLockFileCalls)
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
        )

        val result = executor.executeResultTest(
            preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertEquals(SwitchExecutionStatus.FAILED, result.status)
        val issue = result.issues.single()
        assertEquals(OperationStage.PRE_MUTATION, issue.stage)
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
                // A cancelled read surfaces as OperationCancelledException from the git
                // read boundary; it must propagate as a user cancel, not a failure result.
                throw OperationCancelledException("cancelled")
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            cancelledGit,
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

        assertTrue("a cancelled lock preflight must propagate as cancellation", thrown is OperationCancelledException)
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
                // A cancelled read surfaces as OperationCancelledException from the git
                // read boundary; it must propagate as a user cancel, not a checkpoint failure.
                throw OperationCancelledException("cancelled")
            }
        }
        val executor = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            cancelledGit,
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

        assertTrue("a cancelled checkpoint query must propagate as cancellation", thrown is OperationCancelledException)
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
}
