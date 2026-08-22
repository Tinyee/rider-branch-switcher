package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Specification tests for the Phase 1 stash-based discard (complexity-contraction rev 6).
 *
 * They assert the data-safety property the current delete-based discard violates: an
 * approved collision file must survive a terminal switch outcome, recoverable from its
 * stash. The current implementation permanently deletes approved files, so every test here
 * FAILS against it — hence [Ignore] until Phase 1 replaces deletion with a path-scoped
 * `git stash push -u -- <paths>`. Phase 1 deletes the [Ignore] annotations; it must not
 * change the expectations.
 *
 * The executor-wiring specs (final commit point, round+1 re-stash, no-stash re-validation,
 * ghost-message recovery, drop-failure non-fatality) need the Phase 1 `stashPaths` /
 * `StashPurpose` API and land with Phase 1; this file locks the invariant those specs
 * depend on.
 */
class SwitchStashDiscardSpecTest : SwitchExecutorTestBase() {

    private fun writeUntrackedFile(relative: String): File {
        val file = projectRoot.resolve(relative).toFile()
        file.parentFile.mkdirs()
        file.writeText("local-untracked")
        return file
    }

    /**
     * Reports [collisions] as still-untracked and matching the target tree, mirroring
     * [SwitchCollisionDiscardTest.revalidatingGit], so the current discard step deletes them.
     */
    private fun GitClient.revalidatingGit(collisions: Set<String>): GitClient = object : GitClient by this {
        override fun untrackedFiles(workDir: File): List<String> = collisions.toList()
        override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
            paths.filter { it in collisions }
        override fun resolveTargetRevision(workDir: File, branch: String): String? = "abc123"
        override fun revParseHead(workDir: File): String? = "abc123"
    }

    @Ignore("Phase 1: stash-based discard")
    @Test
    fun `approved collision file survives a failed switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val failingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                GitResult("checkout", 1, "", "checkout failed")
        }.revalidatingGit(setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            failingGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertFalse("a failed checkout must not be reported as success", result.ok)
        assertTrue(
            "the approved file must survive a failed switch, recoverable from its stash",
            collisionFile.exists(),
        )
    }

    @Ignore("Phase 1: stash-based discard")
    @Test
    fun `approved collision file survives a cancelled switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var checks = 0
        // Checks fire before FetchStep (1), before the discard step (2), and before
        // DirtyHandlingStep (3). The discard step runs between checks 2 and 3, so firing
        // on check 3 cancels AFTER the approved file has been isolated.
        val cancellation = object : CancellationHandle {
            override fun checkCanceled() {
                checks++
                if (checks == 3) throw CancellationException("cancel after the discard step")
            }

            override val isCanceled = false
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            fakeGit.revalidatingGit(setOf("Assets/Foo.meta")),
            cancellationHandle = cancellation,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("the switch must be cancelled, not completed", result.cancelled)
        assertTrue(
            "the approved file must survive a cancelled switch, recoverable from its stash",
            collisionFile.exists(),
        )
    }
}
