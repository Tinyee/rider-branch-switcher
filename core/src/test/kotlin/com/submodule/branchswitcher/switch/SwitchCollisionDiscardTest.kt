package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.executeResultTest
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Locks the collision-discard behavior: the discard step, the at-target gate, and the checkout retry. */
class SwitchCollisionDiscardTest : SwitchExecutorTestBase() {

    private fun writeUntrackedFile(relative: String): File {
        val file = projectRoot.resolve(relative).toFile()
        file.parentFile.mkdirs()
        file.writeText("local-untracked")
        return file
    }

    @Test
    fun `discard step deletes approved files before the switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            fakeGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("switch should succeed", result.ok)
        assertFalse("approved file must be deleted", collisionFile.exists())
    }

    @Test
    fun `discard step does not delete files when the repo is already on target`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val onTargetGit = object : GitClient by fakeGit {
            override fun currentBranch(workDir: File): String? = "dev"
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            onTargetGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue(result.ok)
        assertTrue("file must survive when the repo is already on the target branch", collisionFile.exists())
    }

    @Test
    fun `discard step skips deletion when the skip strategy blocks the switch`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions(dirty = DirtyAction.Skip))

        // The Skip strategy reports the dirty repo as a (non-fatal) failure, but it must
        // never reach checkout — so the approved collision files must survive the discard step.
        assertFalse("Skip strategy reports dirty work", result.ok)
        assertTrue(
            "a Skip-strategy switch never reaches checkout, so approved files must survive",
            collisionFile.exists(),
        )
    }

    @Test
    fun `checkout collision retries once after discarding a regenerated file`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var checkoutCalls = 0
        val collidingGit = object : GitClient by fakeGit {
            override fun checkoutExisting(workDir: File, branch: String): GitResult {
                checkoutCalls++
                if (checkoutCalls == 1) {
                    // Simulate Unity regenerating the .meta between the discard step and checkout.
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

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            collidingGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
            steps = listOf(CheckoutStep()),
        ).executeResultTest(preset, SwitchOptions())

        assertTrue("retry must succeed", result.ok)
        assertEquals("checkout must be attempted exactly twice", 2, checkoutCalls)
        assertFalse("regenerated file must be deleted before the retry", collisionFile.exists())
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
            steps = listOf(CheckoutStep()),
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

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            forceDirtyGit,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Force))

        // Under Force the submodule is never stashed, so nothing pre-deletes its approved
        // files; the main failure disables it, and the just-in-time discard never runs.
        assertEquals(SwitchExecutionStatus.PARTIAL, result.status)
        assertTrue(
            "submodule was disabled by the main failure, so its approved file must survive",
            collisionFile.exists(),
        )
    }

    @Test
    fun `dirty Stash submodule is still pre-deleted when a failing main later skips it`() {
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

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            stashDirtyGit,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Stash))

        // Accepted trade-off, locked here so it is not silently "fixed" later: a dirty+Stash
        // repo is pre-deleted before `git stash push -u` sweeps the worktree, and a later
        // gate that skips it cannot bring the files back.
        assertEquals(SwitchExecutionStatus.PARTIAL, result.status)
        assertFalse(
            "dirty+Stash pre-deletes up-front to keep files out of the stash, even when the repo is later skipped",
            collisionFile.exists(),
        )
    }

    @Test
    fun `Force submodule collision file is deleted just-in-time before its checkout`() {
        val submoduleDir = projectRoot.resolve("SubA").toFile()
        submoduleDir.mkdirs()
        submoduleDir.resolve(".git").mkdirs()
        val collisionFile = submoduleDir.resolve("Assets/Foo.meta")
        collisionFile.parentFile.mkdirs()
        collisionFile.writeText("local-untracked")
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = workDir.name == "SubA"
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            collisionDiscards = mapOf("SubA" to setOf("Assets/Foo.meta")),
        ).executeResultTest(Preset("test", "dev", mapOf("SubA" to "dev")), SwitchOptions(dirty = DirtyAction.Force))

        // Under Force the submodule is never stashed, so nothing pre-deletes; the just-in-time
        // discard in BranchCheckout deletes the approved file right before the checkout write.
        assertTrue("switch should succeed", result.ok)
        assertFalse("the approved file must be deleted before the submodule checkout", collisionFile.exists())
    }

    @Test
    fun `dirty Stash repo discards approved files before the stash sweeps the worktree`() {
        val collisionFile = writeUntrackedFile("Assets/Foo.meta")
        var observedAtStash = true
        val dirtyGit = object : GitClient by fakeGit {
            override fun isDirty(workDir: File): Boolean = true
            override fun stash(workDir: File, message: String): GitResult {
                observedAtStash = collisionFile.exists()
                return GitResult("stash", 0, "", "")
            }
        }

        val result = SwitchExecutor(
            projectRoot,
            createStringAppender { log += it },
            dirtyGit,
            collisionDiscards = mapOf("." to setOf("Assets/Foo.meta")),
        ).executeResultTest(preset, SwitchOptions(dirty = DirtyAction.Stash))

        // The discard must run before `git stash push -u` so the approved collision
        // files are not swept into the WIP backup (they would otherwise re-collide with
        // the freshly checked-out tracked versions on restore).
        assertTrue(result.ok)
        assertFalse("the approved file must be gone before git stash runs", observedAtStash)
        assertFalse(collisionFile.exists())
    }
}
