package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Specification tests for the Phase 1 stash-based discard.
 *
 * They assert the data-safety property the current delete-based discard violates: an
 * approved collision file must survive a terminal switch outcome, recoverable from its
 * stash. The current implementation permanently deletes approved files, so every test here
 * FAILS against it — hence [Ignore] until Phase 1 replaces deletion with a path-scoped
 * `git stash push -u -- <paths>`. Phase 1 deletes the [Ignore] annotations; it must not
 * change the expectations.
 *
 * The fake ([StashLifecycleGit]) genuinely simulates the lifecycle so the tests cannot go
 * green on a no-op: it removes the file inside `stashPaths`, records the approved oid, and
 * restores the file inside `stashApply`. The cancellation window is event-driven (the check
 * after isolation fires), not a hard-coded check count.
 */
class SwitchStashDiscardSpecTest : SwitchExecutorTestBase() {

    private fun writeUntrackedFile(relative: String): File {
        val file = projectRoot.resolve(relative).toFile()
        file.parentFile.mkdirs()
        file.writeText("local-untracked")
        return file
    }

    /**
     * A git fake that simulates the approved-stash lifecycle against a real file on disk:
     * `stashPaths` isolates the file (removes it) and records the approved oid; `stashApply`
     * restores it (or fails when [applyFails]); `stashDrop` records the drop. Reports
     * [collision] as still-untracked and matching the target tree, and fails the checkout
     * when [failCheckout]. [afterIsolation] fires once the file has been isolated, so a test
     * can cancel on the very next check.
     */
    private class StashLifecycleGit(
        private val base: GitClient,
        private val collision: Set<String>,
        private val collisionFile: File,
        private val failCheckout: Boolean,
        private val applyFails: Boolean,
        private val afterIsolation: () -> Unit,
    ) : GitClient by base {
        var stashPathsCalls = 0
        var approvedStashCreated = false
        var fileGoneAtIsolation = false
        var stashApplyCalls = 0
        var dropCalls = 0
        var appliedOids = mutableListOf<String>()
        private var approvedOid: String? = null

        override fun untrackedFiles(workDir: File): List<String> = collision.toList()

        override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
            paths.filter { it in collision }

        override fun headStructuralCollisions(workDir: File, paths: List<String>): List<String> =
            paths.filter { it in collision }

        override fun checkoutExisting(workDir: File, branch: String): GitResult =
            if (failCheckout) GitResult("checkout", 1, "", "checkout failed")
            else base.checkoutExisting(workDir, branch)

        override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult {
            stashPathsCalls++
            val present = collisionFile.exists()
            collisionFile.delete()
            fileGoneAtIsolation = present && !collisionFile.exists()
            approvedStashCreated = true
            approvedOid = "approved-oid"
            afterIsolation()
            return GitResult("stash paths", 0, "", "")
        }

        override fun stashTopOid(workDir: File): String? = approvedOid

        override fun stashOidByMessage(workDir: File, messagePrefix: String): String? = approvedOid

        override fun stashApply(workDir: File, oid: String): GitResult {
            stashApplyCalls++
            appliedOids += oid
            if (applyFails) return GitResult("stash apply", 1, "", "apply failed")
            collisionFile.parentFile.mkdirs()
            collisionFile.writeText("restored")
            return GitResult("stash apply", 0, "", "")
        }

        override fun stashDrop(workDir: File, oid: String): GitResult {
            dropCalls++
            return GitResult("stash drop", 0, "", "")
        }
    }

    @Test
    fun `approved collision file survives a failed switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val fake = StashLifecycleGit(
            fakeGit, setOf("Assets/Foo.meta"), collisionFile,
            failCheckout = true, applyFails = false, afterIsolation = {},
        )

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            fake,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertFalse("a failed checkout must not be reported as success", result.ok)
        assertTrue("stashPaths must isolate the approved files", fake.stashPathsCalls == 1)
        assertTrue("the approved stash must actually be created", fake.approvedStashCreated)
        assertTrue("the approved file must be gone at isolation time", fake.fileGoneAtIsolation)
        assertEquals(
            "the restore must apply the recorded approved oid",
            listOf("approved-oid"),
            fake.appliedOids,
        )
        assertEquals("the approved stash must be applied back after the failed checkout", 1, fake.stashApplyCalls)
        assertEquals("a successfully applied stash must be dropped", 1, fake.dropCalls)
        assertTrue("the approved file must be restored", collisionFile.exists())
    }

    @Test
    fun `approved collision file survives a cancelled switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var cancelRequested = false
        val fake = StashLifecycleGit(
            fakeGit, setOf("Assets/Foo.meta"), collisionFile,
            failCheckout = false, applyFails = false, afterIsolation = { cancelRequested = true },
        )
        val cancellation = object : OperationControl {
            override fun checkCancelled() {
                // Event-driven: cancel on the check after the approved stash was isolated,
                // so the test always covers the post-isolation window.
                if (cancelRequested) throw OperationCancelledException("cancelled after approved stash")
            }

            override val isCanceled: Boolean get() = cancelRequested
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            fake,
            operationControl = cancellation,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("the switch must be cancelled, not completed", result.cancelled)
        assertTrue("the cancellation must fire only after the approved stash was isolated", fake.approvedStashCreated)
        assertTrue("the approved file must be gone at isolation time", fake.fileGoneAtIsolation)
        assertTrue(
            "the cancelled switch must keep the approved stash tracked for recovery",
            result.state.stashesSnapshot().any {
                it.purpose == StashPurpose.APPROVED_DISCARD && it.oid != null
            },
        )

        recovery(fake).recover(result)
        assertEquals("recovery must apply the approved stash back", 1, fake.stashApplyCalls)
        assertEquals("a successfully applied stash must be dropped", 1, fake.dropCalls)
        assertTrue("the approved file must be restored by recovery", collisionFile.exists())
    }

    @Test
    fun `approved stash is kept, never dropped, when the apply fails`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val fake = StashLifecycleGit(
            fakeGit, setOf("Assets/Foo.meta"), collisionFile,
            failCheckout = true, applyFails = true, afterIsolation = {},
        )

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            fake,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertFalse(result.ok)
        assertEquals(1, fake.stashApplyCalls)
        assertEquals("a failed apply must never drop the stash", 0, fake.dropCalls)
        assertTrue(
            "a failed apply must keep the approved stash tracked and marked attempted",
            result.state.stashesSnapshot().any {
                it.purpose == StashPurpose.APPROVED_DISCARD && it.restoreAttempted
            },
        )
        assertFalse("the file must not be restored by a failed apply", collisionFile.exists())
    }
}
