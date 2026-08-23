package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitRepositoryInspection
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.inspectRepositoryStateFallback
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Locks the stash-based collision-isolation behavior: the isolation step, the at-target gate, and the checkout retry. */
class SwitchCollisionDiscardTest : SwitchExecutorTestBase() {

    private fun writeUntrackedFile(relative: String): File {
        val file = projectRoot.resolve(relative).toFile()
        file.parentFile.mkdirs()
        file.writeText("local-untracked")
        return file
    }

    @Test
    fun `isolation step stashes approved files before the switch and drops them on success`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val git = ApprovedStashFake(fakeGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("switch should succeed", result.ok)
        assertEquals("the approved stash must be isolated exactly once", 1, git.stashPathsCalls)
        assertEquals("a successful switch discards the approved stash by drop, never applying it", 1, git.dropCalls)
        assertEquals(0, git.applyCalls)
        assertFalse("approved file must be gone after the isolated stash is dropped", collisionFile.exists())
    }

    @Test
    fun `isolation step does not touch files when the repo is already on target`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val onTargetGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
        }
        val git = ApprovedStashFake(onTargetGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue(result.ok)
        assertTrue("file must survive when the repo is already on the target branch", collisionFile.exists())
        assertEquals("no stash may be created when already on target", 0, git.stashPathsCalls)
    }

    @Test
    fun `isolation step skips stashing when the skip strategy blocks the switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val dirtyGit = object : GitClient by fakeGit {
            override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
                inspectRepositoryStateFallback(workDir)

            override fun isDirty(workDir: File): Boolean = true
        }
        val git = ApprovedStashFake(dirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions(dirty = DirtyAction.Skip))

        // The Skip strategy reports the dirty repo as a (non-fatal) failure, but it must
        // never reach checkout — so the approved collision files must survive the isolation step.
        assertFalse("Skip strategy reports dirty work", result.ok)
        assertTrue(
            "a Skip-strategy switch never reaches checkout, so approved files must survive",
            collisionFile.exists(),
        )
        assertEquals("no approved stash may be created under Skip", 0, git.stashPathsCalls)
    }

    @Test
    fun `checkout collision retries once after isolating a regenerated file`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var checkoutCalls = 0
        val collidingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                if (checkoutCalls == 1) {
                    // Simulate Unity regenerating the .meta between the isolation step and checkout.
                    collisionFile.parentFile.mkdirs()
                    collisionFile.writeText("regenerated")
                    return GitResult(
                        "checkout", 1, "",
                        "error: The following untracked working tree files would be overwritten by checkout:\n" +
                            "\tAssets/Foo.meta",
                    )
                }
                return GitResult("checkout", 0, "", "")
            }
        }
        val git = ApprovedStashFake(collidingGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("retry must succeed", result.ok)
        assertEquals("checkout must be attempted exactly twice", 2, checkoutCalls)
        assertEquals(
            "the pre-stash isolates round 0 and the retry re-isolates round 1",
            2,
            git.stashPathsCalls,
        )
        assertFalse("regenerated file must be gone after the retry", collisionFile.exists())
    }

    @Test
    fun `checkout collision without approval is not retried`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var checkoutCalls = 0
        val collidingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                return GitResult(
                    "checkout", 1, "",
                    "error: The following untracked working tree files would be overwritten by checkout:\n" +
                        "\tAssets/Foo.meta",
                )
            }
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            collidingGit,
            collisionDiscards = emptyMap(),
        ).executeResultTest(preset, SwitchOptions())

        assertFalse("a collision without approval must still fail", result.ok)
        assertEquals(1, checkoutCalls)
        assertTrue("file must survive without approval", collisionFile.exists())
        assertTrue(result.issues.any { it.code == OperationIssueCode.CHECKOUT_FAILED })
    }

    @Test
    fun `main checkout failure leaves a Force-switched submodule's approved files intact`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        val forceDirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = workDir.name == "SubA"
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                if (workDir.canonicalPath == projectRoot.toFile().canonicalPath) {
                    GitResult("checkout", 1, "", "failed to switch main")
                } else {
                    fakeGit.checkoutExisting(workDir, branch)
                }
        }
        val git = ApprovedStashFake(forceDirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Force))

        // The main failure disables the submodule before any isolation, so its approved
        // files are never touched.
        assertEquals(SwitchExecutionStatus.PARTIAL, result.status)
        assertTrue(
            "submodule was disabled by the main failure, so its approved file must survive",
            collisionFile.exists(),
        )
        assertEquals("no approved stash may be created for a disabled submodule", 0, git.stashPathsCalls)
    }

    @Test
    fun `dirty Stash submodule keeps its approved files when a failing main later skips it`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        val stashDirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = workDir.name == "SubA"
            override fun checkoutExisting(workDir: File, branch: String): GitResult =
                if (workDir.canonicalPath == projectRoot.toFile().canonicalPath) {
                    GitResult("checkout", 1, "", "failed to switch main")
                } else {
                    fakeGit.checkoutExisting(workDir, branch)
                }
        }
        val git = ApprovedStashFake(stashDirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Stash))

        // Submodule isolation runs inside SubmoduleTreeStep, after the topology gate. A failing
        // main disables the submodule before any stash, so its approved files survive even under Stash.
        assertEquals(SwitchExecutionStatus.PARTIAL, result.status)
        assertTrue(
            "a submodule skipped because the main failed must never have its files isolated",
            collisionFile.exists(),
        )
        assertEquals("no approved stash may be created for a disabled submodule", 0, git.stashPathsCalls)
    }

    @Test
    fun `Force submodule collision file is isolated before its checkout`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = workDir.name == "SubA"
        }
        val git = ApprovedStashFake(dirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Force))

        // Under Force the submodule is never WIP-stashed, but its approved collision files are
        // still isolated before its checkout and dropped on success.
        assertTrue("switch should succeed", result.ok)
        assertEquals("the submodule's approved files must be isolated", 1, git.stashPathsCalls)
        assertEquals("a successful submodule switch drops the approved stash", 1, git.dropCalls)
        assertFalse("the approved file must be gone after the submodule checkout", collisionFile.exists())
    }

    @Test
    fun `dirty Stash repo isolates approved files before the WIP stash sweeps the worktree`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var observedAtWipStash = true
        val dirtyGit = object : GitClient by fakeGit {
            override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
                inspectRepositoryStateFallback(workDir)

            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                observedAtWipStash = collisionFile.exists()
                return GitResult("stash", 0, "", "")
            }
        }
        val git = ApprovedStashFake(dirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions(dirty = DirtyAction.Stash))

        // Isolation must run before `git stash push -u` so the approved collision files are
        // not swept into the WIP backup (they would otherwise re-collide with the freshly
        // checked-out tracked versions on restore).
        assertTrue(result.ok)
        assertFalse("the approved file must be gone before git stash runs", observedAtWipStash)
        assertFalse(collisionFile.exists())
    }

    @Test
    fun `dirty Stash submodule isolates approved files before its WIP stash inside the submodule flow`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        var observedAtWipStash = true
        val dirtyGit = object : GitClient by fakeGit {
            override fun inspectRepositoryState(workDir: File): GitRepositoryInspection =
                inspectRepositoryStateFallback(workDir)

            override fun isDirty(workDir: File): Boolean = workDir.name == "SubA"
            override fun stash(workDir: File, message: String): GitResult {
                if (workDir.name == "SubA") observedAtWipStash = collisionFile.exists()
                return GitResult("stash", 0, "", "")
            }
        }
        val git = ApprovedStashFake(dirtyGit, setOf("Assets/Foo.meta"))

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            git,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Stash))

        assertTrue("switch should succeed", result.ok)
        assertFalse("the submodule's approved file must be gone before its WIP stash runs", observedAtWipStash)
        assertFalse(collisionFile.exists())
    }

    @Test
    fun `stash messages are operation-scoped and never leak repository paths`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val messages = mutableListOf<String>()
        val capturingGit = object : GitClient by fakeGit {
            override fun untrackedFiles(workDir: File): List<String> = listOf("Assets/Foo.meta")
            override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
                paths.filter { it in setOf("Assets/Foo.meta") }
            override fun headStructuralCollisions(workDir: File, paths: List<String>): List<String> = paths
            override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult {
                messages += message
                File(workDir, "Assets/Foo.meta").delete()
                return GitResult("stash paths", 0, "", "")
            }
            override fun stashOidByMessage(workDir: File, messagePrefix: String): String? = "approved-oid"
            override fun stashDrop(workDir: File, oid: String): GitResult = GitResult("stash drop", 0, "", "")
        }

        fun runOnce() {
            SwitchExecutor(
                projectRoot,
                createStringAppender { log += it },
                capturingGit,
                collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
            ).executeResultTest(preset, SwitchOptions())
        }
        runOnce()
        val first = messages.single()
        assertTrue("the message must carry the approved-discard marker", first.contains("approved-discard"))
        assertFalse("the message must not leak the repository path", first.contains("Assets"))
        messages.clear()

        // A second execution mints a fresh operation id, so a retained stash from an earlier
        // run can never be matched by message and applied or dropped as this run's own.
        writeUntrackedFile("Assets/Foo.meta") // the first run isolated (deleted) the file
        runOnce()
        assertNotEquals("each execution must use a fresh operation-scoped message", first, messages.single())
    }

    @Test
    fun `completed-switch retry drops an approved stash an interrupted restore left behind`() {
        // A SUCCESS switch whose inline restore interrupted the WIP apply (marked attempted)
        // before the approved stash was processed. The stash-only retry must NOT apply the
        // approved stash onto the already-switched tree — the discard is authorized — it must
        // drop it.
        val git = ApprovedStashFake(fakeGit, setOf("Assets/Foo.meta"))
        val state = SwitchState()
            .withTrackedStash(
                ".",
                StashPurpose.APPROVED_DISCARD,
                "approved",
                "approved-oid",
                approvedPaths = setOf("Assets/Foo.meta"),
            )
            .withTrackedStash(".", StashPurpose.WIP_RESTORE_AFTER_SWITCH, "wip", "wip-oid")
            .withStashRestoreAttempted("stash-1") // the WIP (created second) was interrupted
            .withSuccessfulCheckout(".")
        val result = SwitchExecutionResult(
            status = SwitchExecutionStatus.SUCCESS,
            checkpoint = mapOf("." to CheckpointEntry(sha = "abc123", branch = "dev")),
            state = state,
        )

        val restore = recovery(git).retryCompletedRestore(result)

        assertEquals("the retry must not apply the approved stash onto the switched tree", 0, git.applyCalls)
        assertEquals("the retry must drop the approved stash as authorized", 1, git.dropCalls)
        assertTrue(
            "the approved stash must leave tracking after the authorized drop",
            restore.state.stashesSnapshot().none { it.purpose == StashPurpose.APPROVED_DISCARD },
        )
    }

    @Test
    fun `submodule isolation that cannot stash disables the submodule and reports the issue`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        val failingIsolation = object : GitClient by fakeGit {
            override fun untrackedFiles(workDir: File): List<String> =
                if (workDir.name == "SubA") listOf("Assets/Foo.meta") else emptyList()
            override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
                paths.filter { it in setOf("Assets/Foo.meta") }
            override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult =
                if (workDir.name == "SubA") {
                    GitResult("stash paths", 1, "", "cannot stash")
                } else {
                    GitResult("stash paths", 0, "", "")
                }
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            failingIsolation,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(
            Preset("test", "dev", mapOf("SubA" to "dev")),
            SwitchOptions(dirty = DirtyAction.Force),
        )

        assertTrue("the isolation failure must disable the submodule", result.state.isSkipped("SubA"))
        assertTrue(
            "the isolation failure must be reported, not silently discarded",
            result.issues.any { it.code == OperationIssueCode.STASH_FAILED },
        )
        assertTrue("the approved file must survive a failed isolation", collisionFile.exists())
    }

    @Test
    fun `approved stash is retained when the checked-out tree no longer conflicts`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var dropCalls = 0
        val driftingGit = object : GitClient by fakeGit {
            override fun untrackedFiles(workDir: File): List<String> = listOf("Assets/Foo.meta")
            override fun targetBranchMatches(workDir: File, branch: String, paths: List<String>): List<String> =
                paths.filter { it in setOf("Assets/Foo.meta") }
            // The target ref moved after the collision validation: the actual checked-out
            // tree no longer tracks the approved path, so a drop would discard it for nothing.
            override fun headStructuralCollisions(workDir: File, paths: List<String>): List<String> = emptyList()
            override fun stashPaths(workDir: File, message: String, paths: Collection<String>): GitResult {
                File(workDir, "Assets/Foo.meta").delete()
                return GitResult("stash paths", 0, "", "")
            }
            override fun stashOidByMessage(workDir: File, messagePrefix: String): String? = "approved-oid"
            override fun stashDrop(workDir: File, oid: String): GitResult {
                dropCalls++
                return GitResult("stash drop", 0, "", "")
            }
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            driftingGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue(result.ok)
        assertEquals("a drifted checked-out tree must prevent the approved drop", 0, dropCalls)
        assertTrue(
            "the unverifiable approved stash must be retained as a backup",
            result.state.retainedStashBackupsSnapshot().isNotEmpty(),
        )
        assertTrue(
            "the retained stash must be reported as an issue",
            result.issues.any { it.code == OperationIssueCode.UNTRACKED_DISCARD_FAILED },
        )
    }
}
