
package com.submodule.branchswitcher.switch
import com.submodule.branchswitcher.executeTest
import com.submodule.branchswitcher.executeResultTest

import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Integration tests using real git repositories in temp directories.
 * Each test creates actual git repos, runs the full SwitchExecutor pipeline via GitOps,
 * and verifies the resulting git state.
 */
@Suppress("LargeClass")
class SwitchIntegrationTest {

    private lateinit var tmpDir: Path
    private lateinit var git: GitOps
    private val log = mutableListOf<String>()

    private fun recovery(root: File) =
        SwitchRecoveryExecutor(root.toPath(), createStringAppender { log += it }, git)

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("switch-it-")
        git = GitOps(timeoutSeconds = 30, processStarter = { builder ->
            builder.environment()["GIT_ALLOW_PROTOCOL"] = "file"
            builder.start()
        })
        log.clear()
    }

    @After
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    // ---- Helpers ----

    private fun runGit(dir: File, vararg args: String): Pair<Int, String> {
        val proc = ProcessBuilder("git", *args)
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        val exit = proc.waitFor()
        return exit to out
    }

    private fun gitOk(dir: File, vararg args: String): String {
        val (code, out) = runGit(dir, *args)
        assertEquals("git ${args.joinToString(" ")} should succeed in ${dir.name}: $out", 0, code)
        return out
    }

    /** Create a repo with an initial commit on [branch]. Returns the repo dir. */
    private fun createRepo(parent: Path, name: String, branch: String = "main"): File {
        val dir = parent.resolve(name).toFile().also { it.mkdirs() }
        // Use two-step init for compatibility with older git (no -b flag)
        gitOk(dir, "init")
        gitOk(dir, "checkout", "-b", branch)
        gitOk(dir, "config", "user.email", "test@test.com")
        gitOk(dir, "config", "user.name", "Test")
        // Disable CRLF conversion so line endings are predictable in tests
        gitOk(dir, "config", "core.autocrlf", "false")
        File(dir, "README.md").writeText("# $name\n")
        gitOk(dir, "add", "README.md")
        gitOk(dir, "commit", "-m", "initial $name")
        return dir
    }

    /** Create a second branch in [dir] with a unique file. */
    private fun createBranch(dir: File, branch: String) {
        val cur = git.currentBranch(dir)
        gitOk(dir, "checkout", "-b", branch)
        File(dir, "file-$branch.txt").writeText("$branch content\n")
        gitOk(dir, "add", ".")
        gitOk(dir, "commit", "-m", "commit on $branch")
        if (cur != null) gitOk(dir, "checkout", cur)
    }

    /** Add [subDir] as a submodule to [mainDir] at [path] using a relative path. */
    private fun addSubmodule(mainDir: File, subDir: File, path: String) {
        // Compute a relative path from mainDir to subDir (both under tmpDir)
        val rel = mainDir.toPath().relativize(subDir.toPath()).toString().replace('\\', '/')
        gitOk(mainDir, "-c", "protocol.file.allow=always", "submodule", "add", rel, path)
        gitOk(mainDir, "commit", "-m", "add submodule $path")
    }

    private fun createBareClone(source: File, name: String): File {
        val target = tmpDir.resolve(name).toFile()
        gitOk(tmpDir.toFile(), "clone", "--bare", source.absolutePath, target.absolutePath)
        return target
    }

    private fun cloneRepo(source: File, name: String): File {
        val target = tmpDir.resolve(name).toFile()
        gitOk(tmpDir.toFile(), "clone", source.absolutePath, target.absolutePath)
        gitOk(target, "config", "user.email", "test@test.com")
        gitOk(target, "config", "user.name", "Test")
        gitOk(target, "config", "core.autocrlf", "false")
        return target
    }

    /** Execute a switch and return (success, log lines). */
    private fun runSwitch(root: File, preset: Preset, opts: SwitchOptions): Pair<Boolean, List<String>> {
        log.clear()
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)
        val ok = executor.executeTest(preset, opts)
        return ok to log.toList()
    }

    // ========================================================================
    // Tests
    // ========================================================================

    // ---- Branch not found ----

    // ---- Full switch with submodules ----

    @Test
    fun `switch main and submodules to target branches`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        val subB = createRepo(tmpDir, "subB-src")
        createBranch(subA, "release")
        createBranch(subB, "feature")
        addSubmodule(root, subA, "SubA")
        addSubmodule(root, subB, "SubB")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val preset = Preset("multi", "main", mapOf("SubA" to "release", "SubB" to "feature"))
        val (ok, _) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Multi-repo switch should succeed", ok)
        assertEquals("main", git.currentBranch(root))
        assertEquals("release", git.currentBranch(File(root, "SubA")))
        assertEquals("feature", git.currentBranch(File(root, "SubB")))
    }

    // ---- Dirty handling ----

    @Test
    fun `dirty skip reports failure and leaves branch unchanged`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        File(root, "dirty.txt").writeText("changes\n")

        val (ok, logs) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Skip, pull = false, fetchFirst = false))
        // DirtyHandlingStep returns Partial (marks failure) but pipeline continues to CheckoutStep
        assertFalse("Dirty+Skip should report overall failure", ok)
        val hasSkip = logs.any { it.contains("skip") || it.contains("dirty") }
        assertTrue("Log should mention dirty skip, got: $logs", hasSkip)
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `dirty force proceeds with dirty working tree`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        File(root, "dirty.txt").writeText("changes\n")

        val (ok, _) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false))
        assertTrue("Dirty+Force should succeed", ok)
        assertEquals("dev", git.currentBranch(root))
        assertTrue("Dirty file should still exist", File(root, "dirty.txt").exists())
    }

    // ---- Submodule init ----

    @Test
    fun `remote parent addition is pulled before cloud-only submodule initialization`() {
        val subAuthor = createRepo(tmpDir, "sub-author")
        createBranch(subAuthor, "release")
        val subRemote = createBareClone(subAuthor, "sub-remote.git")

        val mainAuthor = createRepo(tmpDir, "main-author")
        val mainRemote = createBareClone(mainAuthor, "main-remote.git")
        gitOk(mainAuthor, "remote", "add", "origin", mainRemote.absolutePath)
        val local = cloneRepo(mainRemote, "project")

        addSubmodule(mainAuthor, subRemote, "SubA")
        gitOk(mainAuthor, "push", "origin", "main")

        assertFalse("Local clone should predate .gitmodules", File(local, ".gitmodules").exists())
        assertFalse("Submodule should initially exist only in the remote parent", File(local, "SubA").exists())

        val (ok, logs) = runSwitch(
            local,
            Preset("remote-addition", "main", mapOf("SubA" to "release")),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true),
        )

        assertTrue("Remote-only submodule should be initialized and switched. Logs: $logs", ok)
        assertTrue("Parent pull should bring down .gitmodules", File(local, ".gitmodules").exists())
        val subDir = File(local, "SubA")
        assertTrue("Submodule worktree should be created", subDir.exists())
        assertTrue("Submodule should be a usable git repository", git.isGitRepo(subDir))
        assertEquals("release", git.currentBranch(subDir))
        val mainPull = logs.indexOfFirst { it.contains("pull ok - .") }
        val submoduleInit = logs.indexOfFirst { it.contains("submodule init ok") }
        assertTrue("Main pull must happen before submodule initialization", mainPull >= 0 && mainPull < submoduleInit)
    }

    @Test
    fun `nested child added by parent pull is initialized before the child switch`() {
        val childAuthor = createRepo(tmpDir, "child-author")
        createBranch(childAuthor, "release")
        val childRemote = createBareClone(childAuthor, "child-remote.git")

        val parentAuthor = createRepo(tmpDir, "parent-author")
        val parentRemote = createBareClone(parentAuthor, "parent-remote.git")
        gitOk(parentAuthor, "remote", "add", "origin", parentRemote.absolutePath)

        val mainAuthor = createRepo(tmpDir, "main-author")
        addSubmodule(mainAuthor, parentRemote, "Parent")
        val mainRemote = createBareClone(mainAuthor, "main-remote.git")
        val local = cloneRepo(mainRemote, "project")
        gitOk(local, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--recursive")

        addSubmodule(parentAuthor, childRemote, "Nested")
        gitOk(parentAuthor, "push", "origin", "main")

        val nested = File(local, "Parent/Nested")
        assertFalse("Nested child should initially exist only in the parent remote", nested.exists())

        val (ok, logs) = runSwitch(
            local,
            Preset(
                "nested-addition",
                "main",
                linkedMapOf("Parent" to "main", "Parent/Nested" to "release"),
            ),
            SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true),
        )

        assertTrue("Nested child should initialize after its parent pull. Logs: $logs", ok)
        assertTrue("Nested child worktree should be created", git.isGitRepo(nested))
        assertEquals("release", git.currentBranch(nested))
        val parentPull = logs.indexOfFirst { it.contains("pull ok - Parent") }
        val nestedInit = logs.indexOfFirst { it.contains("submodule init ok") }
        assertTrue("Parent pull must happen before nested initialization. Logs: $logs", parentPull >= 0)
        assertTrue("Nested initialization should be logged. Logs: $logs", nestedInit >= 0)
        assertTrue("Parent pull must precede nested initialization. Logs: $logs", parentPull < nestedInit)
    }

    @Test
    fun `moved submodule initializes its new path without deleting the obsolete worktree`() {
        val submoduleSource = createRepo(tmpDir, "submodule-source")
        createBranch(submoduleSource, "release")

        val mainAuthor = createRepo(tmpDir, "main-author")
        addSubmodule(mainAuthor, submoduleSource, "modules/old-path")
        gitOk(mainAuthor, "checkout", "-b", "moved")
        gitOk(mainAuthor, "mv", "modules/old-path", "modules/new-path")
        gitOk(mainAuthor, "commit", "-m", "move submodule path")
        gitOk(mainAuthor, "checkout", "main")

        val mainRemote = createBareClone(mainAuthor, "main-remote.git")
        val local = cloneRepo(mainRemote, "project")
        gitOk(local, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--recursive")

        val oldPath = File(local, "modules/old-path")
        val newPath = File(local, "modules/new-path")
        assertTrue("The old submodule should be initialized before switching", git.isGitRepo(oldPath))
        assertFalse("The new submodule path should not exist before switching", newPath.exists())

        val (ok, logs) = runSwitch(
            local,
            Preset("moved", "moved", mapOf("modules/new-path" to "release")),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )

        assertTrue("The moved submodule should initialize and switch. Logs: $logs", ok)
        assertEquals("moved", git.currentBranch(local))
        assertTrue("The new submodule worktree should be initialized", git.isGitRepo(newPath))
        assertEquals("release", git.currentBranch(newPath))
        assertTrue("The plugin must not delete the obsolete worktree automatically", oldPath.exists())
        assertFalse(
            "The obsolete path should no longer be tracked by the target parent branch",
            runGit(local, "ls-files", "--error-unmatch", "modules/old-path").first == 0,
        )
    }

    @Test
    fun `swapped submodule paths never operate on the worktree from the old path`() {
        val submoduleA = createRepo(tmpDir, "submodule-a")
        val submoduleB = createRepo(tmpDir, "submodule-b")
        createBranch(submoduleA, "release-a")
        createBranch(submoduleB, "release-b")

        val mainAuthor = createRepo(tmpDir, "main-author")
        addSubmodule(mainAuthor, submoduleA, "SubA")
        addSubmodule(mainAuthor, submoduleB, "SubB")
        gitOk(mainAuthor, "checkout", "-b", "swapped")
        gitOk(mainAuthor, "mv", "SubA", "SwapTemp")
        gitOk(mainAuthor, "mv", "SubB", "SubA")
        gitOk(mainAuthor, "mv", "SwapTemp", "SubB")
        gitOk(mainAuthor, "commit", "-m", "swap submodule paths")
        gitOk(mainAuthor, "checkout", "main")

        val mainRemote = createBareClone(mainAuthor, "main-remote.git")
        val local = cloneRepo(mainRemote, "project")
        gitOk(local, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--recursive")
        val oldAWorktree = File(local, "SubA")
        val oldBWorktree = File(local, "SubB")

        val (ok, logs) = runSwitch(
            local,
            Preset(
                "swapped",
                "swapped",
                linkedMapOf("SubA" to "release-b", "SubB" to "release-a"),
            ),
            SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false),
        )

        assertFalse("Old worktrees must be rejected after their registered paths are swapped", ok)
        assertNotEquals("release-b", git.currentBranch(oldAWorktree))
        assertNotEquals("release-a", git.currentBranch(oldBWorktree))
        assertTrue(logs.any { it.contains("not associated with its superproject") })
    }

    @Test
    fun `repository replacement at the same submodule path never reuses the old worktree`() {
        val originalSubmodule = createRepo(tmpDir, "original-submodule")
        val replacementSubmodule = createRepo(tmpDir, "replacement-submodule")
        createBranch(replacementSubmodule, "replacement-release")

        val mainAuthor = createRepo(tmpDir, "main-author")
        addSubmodule(mainAuthor, originalSubmodule, "SubA")
        gitOk(mainAuthor, "checkout", "-b", "replacement")
        val replacementUrl = mainAuthor.toPath()
            .relativize(replacementSubmodule.toPath())
            .toString()
            .replace('\\', '/')
        gitOk(
            mainAuthor,
            "config",
            "-f",
            ".gitmodules",
            "submodule.SubA.url",
            replacementUrl,
        )
        gitOk(mainAuthor, "add", ".gitmodules")
        gitOk(mainAuthor, "commit", "-m", "replace submodule repository")
        gitOk(mainAuthor, "checkout", "main")

        val mainRemote = createBareClone(mainAuthor, "main-remote.git")
        val local = cloneRepo(mainRemote, "project")
        gitOk(local, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "--recursive")
        val oldWorktree = File(local, "SubA")

        val (ok, logs) = runSwitch(
            local,
            Preset("replacement", "replacement", mapOf("SubA" to "replacement-release")),
            SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false),
        )

        assertFalse("Changing the registered repository must not reuse its old worktree", ok)
        assertNotEquals("replacement-release", git.currentBranch(oldWorktree))
        assertTrue(logs.any { it.contains("registered repository remote changed") })
    }

    @Test
    fun `registered path occupied by a standalone repository is never modified`() {
        val root = createRepo(tmpDir, "project")
        val expectedSubmodule = createRepo(tmpDir, "expected-submodule")
        createBranch(expectedSubmodule, "release")
        addSubmodule(root, expectedSubmodule, "SubA")
        gitOk(root, "submodule", "deinit", "-f", "--", "SubA")
        File(root, "SubA").deleteRecursively()

        val standalone = createRepo(root.toPath(), "SubA")
        createBranch(standalone, "release")
        assertTrue(
            "Standalone repository metadata should remain inside its worktree",
            git.repositoryIdentity(standalone)?.gitDirectory?.startsWith(standalone.canonicalPath) == true,
        )

        val (ok, logs) = runSwitch(
            root,
            Preset("wrong-worktree", "main", mapOf("SubA" to "release")),
            SwitchOptions(DirtyAction.Force, pull = false, fetchFirst = false),
        )

        assertFalse("Switch must reject a standalone repository at a registered path", ok)
        assertEquals("main", git.currentBranch(standalone))
        assertTrue(logs.any { it.contains("not associated with its superproject") })
    }

    // ---- Rollback ----

    @Test
    fun `rollback restores checkpoint HEAD when current branch name is unchanged`() {
        val root = createRepo(tmpDir, "project")
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)
        val result = executor.executeResultTest(
            Preset("test", "main"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false),
        )
        val checkpointSha = result.checkpoint?.get(".")?.sha
        assertNotNull(checkpointSha)

        File(root, "later.txt").writeText("later\n")
        gitOk(root, "add", "later.txt")
        gitOk(root, "commit", "-m", "advance main")
        assertNotEquals(checkpointSha, git.revParseHead(root))

        assertTrue(recovery(root).rollback(result))
        assertEquals("main", git.currentBranch(root))
        assertEquals(checkpointSha, git.revParseHead(root))
        assertFalse(File(root, "later.txt").exists())
    }

    // ---- Submodule with missing branch ----

    @Test
    fun `partial failure on submodule with missing branch`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val preset = Preset("missing-branch", "main", mapOf("SubA" to "no-branch"))
        val (ok, logs) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertFalse("Switch should fail when submodule branch doesn't exist", ok)
        val hasSubFail = logs.any { (it.contains("[fail]") || it.contains("[error]") || it.contains("[warn]")) && it.contains("SubA") }
        assertTrue("Log should contain SubA failure, got: $logs", hasSubFail)
    }

    // ---- Derive branch (via DeriveBranchExecutor) ------------------------------

    private fun deriveLog(): AppLogger = createStringAppender { log += it }

    @Test
    fun `derive on all clean repos succeeds`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "feature")

        assertTrue("derive should be allOk", result.allOk)
        assertEquals(2, result.actualCreated)
        assertEquals("feature", git.currentBranch(root))
        assertEquals("feature", git.currentBranch(File(root, "SubA")))
    }

    @Test
    fun `derive rollback restores original branch and deletes derived branch`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val rootBranch = git.currentBranch(root)
        val subABranch = git.currentBranch(File(root, "SubA"))

        // Derive on both repos
        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "derived")

        assertTrue("derive should succeed", result.allOk)
        assertEquals("derived", git.currentBranch(root))
        assertEquals("derived", git.currentBranch(File(root, "SubA")))

        // Rollback
        val failures = executor.rollbackSucceeded(result, "derived")
        assertTrue("rollback should have no failures", failures.isEmpty())

        // Verify restored
        assertEquals(rootBranch, git.currentBranch(root))
        assertEquals(subABranch, git.currentBranch(File(root, "SubA")))

        // Verify branches were deleted
        assertFalse("derived branch should be deleted on main", git.localBranchExists(root, "derived"))
        assertFalse("derived branch should be deleted on SubA", git.localBranchExists(File(root, "SubA"), "derived"))
    }

    @Test
    fun `derive blocks detached HEAD because it does not match preset branch`() {
        val root = createRepo(tmpDir, "project")
        val sha = git.revParseHead(root)
        gitOk(root, "checkout", sha!!) // detach HEAD

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main")
        val result = executor.execute(preset, "derived-detached")

        assertTrue("detached HEAD should block preflight", result.preflightBlocked)
        assertEquals(listOf("."), result.branchMismatch)
        assertEquals(0, result.actualCreated)
        assertFalse("derived branch should be deleted", git.localBranchExists(root, "derived-detached"))
    }

    @Test
    fun `derive blocks all repos when one repo does not match preset base branch`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")
        gitOk(File(root, "SubA"), "checkout", "-b", "feature-x")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "derived")

        assertTrue(result.preflightBlocked)
        assertEquals(listOf("SubA"), result.branchMismatch)
        assertEquals("main", git.currentBranch(root))
        assertFalse(git.localBranchExists(root, "derived"))
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `derive blocks when current branch probe throws`() {
        val root = createRepo(tmpDir, "project")
        val throwingGit = object : GitClient by git {
            override fun currentBranch(workDir: java.io.File): String? =
                throw RuntimeException("current branch probe failed")
        }

        val result = DeriveBranchExecutor(root.toPath(), deriveLog(), throwingGit)
            .execute(Preset("test", "main"), "derived")

        assertTrue(result.preflightBlocked)
        assertEquals(listOf("."), result.preflightError)
        assertFalse(git.localBranchExists(root, "derived"))
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `derive blocks when branch existence probe throws`() {
        val root = createRepo(tmpDir, "project")
        val throwingGit = object : GitClient by git {
            override fun localBranchProbe(workDir: java.io.File, branch: String): Boolean =
                throw RuntimeException("branch probe failed")
        }

        val result = DeriveBranchExecutor(root.toPath(), deriveLog(), throwingGit)
            .execute(Preset("test", "main"), "derived")

        assertTrue(result.preflightBlocked)
        assertEquals(listOf("."), result.preflightError)
        assertFalse(git.localBranchExists(root, "derived"))
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `derive blocks when dirty probe throws`() {
        val root = createRepo(tmpDir, "project")
        val throwingGit = object : GitClient by git {
            override fun dirtyProbe(workDir: java.io.File): Boolean =
                throw RuntimeException("dirty probe failed")
        }

        val result = DeriveBranchExecutor(root.toPath(), deriveLog(), throwingGit)
            .execute(Preset("test", "main"), "derived")

        assertTrue(result.preflightBlocked)
        assertEquals(listOf("."), result.preflightError)
        assertFalse(git.localBranchExists(root, "derived"))
    }

    @Test(expected = com.intellij.openapi.progress.ProcessCanceledException::class)
    fun `derive rethrows ProcessCanceledException instead of converting to preflight error`() {
        val root = createRepo(tmpDir, "project")
        val cancellingGit = object : GitClient by git {
            override fun currentBranch(workDir: java.io.File): String? =
                throw com.intellij.openapi.progress.ProcessCanceledException()
        }

        DeriveBranchExecutor(root.toPath(), deriveLog(), cancellingGit)
            .execute(Preset("test", "main"), "derived")
    }

    @Test
    fun `derive rollback continues after partial failures`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val subADir = File(root, "SubA")
        val rootBranch = git.currentBranch(root)

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "derived")

        assertTrue("derive should succeed", result.allOk)

        // Delete the derived branch on root BEFORE rollback, so root rollback fails
        gitOk(root, "checkout", rootBranch!!)
        gitOk(root, "branch", "-D", "derived")

        // Rollback — root has no "derived" branch to delete, SubA succeeds
        val failures = executor.rollbackSucceeded(result, "derived")
        // Root: checkout to main should work, but delete "derived" fails (already gone)
        // SubA: both checkout and delete should work
        assertFalse("rollback should have some failures", failures.isEmpty())

        // SubA should still be restored
        assertEquals("main", git.currentBranch(subADir))
        assertFalse("derived branch should be deleted on SubA", git.localBranchExists(subADir, "derived"))
    }

    @Test
    fun `derive with invalid submodules blocks and does not modify main`() {
        val root = createRepo(tmpDir, "project")
        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("ghost" to "main", "phantom" to "main"))
        val result = executor.execute(preset, "feature")

        assertTrue("preflight should block when submodules are missing", result.preflightBlocked)
        assertEquals(0, result.actualCreated)
        assertEquals(2, result.skipped.size)
        // Main must NOT be modified — atomic gate
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `derive blocks an obsolete retained submodule worktree`() {
        val root = createRepo(tmpDir, "project")
        val submoduleSource = createRepo(tmpDir, "submodule-source")
        addSubmodule(root, submoduleSource, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val obsoleteWorktree = File(root, "SubA")
        gitOk(root, "rm", "--cached", "SubA")
        assertTrue(File(root, ".gitmodules").delete())
        gitOk(root, "add", "-u")
        gitOk(root, "commit", "-m", "remove submodule registration")
        assertTrue("Obsolete worktree should remain available locally", git.isGitRepo(obsoleteWorktree))

        val result = DeriveBranchExecutor(root.toPath(), deriveLog(), git).execute(
            Preset("obsolete", "main", mapOf("SubA" to "main")),
            "feature",
        )

        assertTrue(result.preflightBlocked)
        assertEquals(listOf("SubA"), result.skipped)
        assertFalse(git.localBranchExists(root, "feature"))
        assertFalse(git.localBranchExists(obsoleteWorktree, "feature"))
    }

    @Test
    fun `derive blocks on dirty repo when requireClean is true`() {
        val root = createRepo(tmpDir, "project")
        File(root, "dirty.txt").writeText("uncommitted")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git, requireClean = true)
        val preset = Preset("test", "main")
        val result = executor.execute(preset, "feature")

        assertTrue("preflight should block on dirty", result.preflightBlocked)
        assertEquals(1, result.dirty.size)
        assertEquals(0, result.actualCreated)
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `derive allows dirty repo when requireClean is false`() {
        val root = createRepo(tmpDir, "project")
        File(root, "dirty.txt").writeText("uncommitted")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git, requireClean = false)
        val preset = Preset("test", "main")
        val result = executor.execute(preset, "feature")

        assertTrue("derive should succeed with requireClean=false", result.allOk)
        assertEquals(1, result.actualCreated)
        assertEquals("feature", git.currentBranch(root))
    }

    @Test
    fun `derive blocks when submodule already has target branch`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")
        // Pre-create the target branch on SubA only
        val subADir = File(root, "SubA")
        gitOk(subADir, "checkout", "-b", "feature")
        gitOk(subADir, "checkout", "main")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "feature")

        assertTrue("preflight should block", result.preflightBlocked)
        assertEquals(1, result.branchExists.size)
        assertEquals(0, result.actualCreated)
        // Main must NOT be modified — atomic gate
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `derive blocks on empty repo with no HEAD`() {
        val root = tmpDir.resolve("empty-project").toFile().also { it.mkdirs() }
        gitOk(root, "init")
        // No commits → no HEAD
        val defaultBranch = git.currentBranch(root) ?: "main"

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", defaultBranch)
        val result = executor.execute(preset, "feature")

        assertTrue("empty repo should block", result.checkpointBlocked || result.preflightBlocked)
        assertEquals(0, result.actualCreated)
    }

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `per-target exception is caught and reported in failed`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        // Use a custom GitClient that throws on SubA
        var subACalled = false
        val throwingGit = ThrowingGitClient(git) { workDir ->
            if (workDir.name == "SubA") { subACalled = true; true } else false
        }

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), throwingGit)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "feature")

        assertEquals("main should succeed", 1, result.succeeded.size)
        assertTrue("SubA should have been called", subACalled)
        assertEquals("SubA should be in failed", 1, result.failedOutcomes.size)
        val failedOutcome = result.failedOutcomes.single()
        assertEquals("SubA", failedOutcome.repositoryPath)
        assertTrue(failedOutcome.issue?.diagnostic.orEmpty().contains("simulated crash"))
        assertFalse("allOk false when one failed", result.allOk)

        // Rollback: main should be restored, derived branch deleted
        val failures = executor.rollbackSucceeded(result, "feature")
        assertTrue("rollback should have no failures", failures.isEmpty())
        assertEquals("main should be restored to original branch", "main", git.currentBranch(root))
        assertFalse("derived branch on main should be deleted", git.localBranchExists(root, "feature"))
    }

    // -- helpers ---------------------------------------------------------------

    /** Delegates to [inner] except where [shouldThrow] returns true for checkoutNewBranch. */
    @Suppress("TooGenericExceptionThrown")
    private class ThrowingGitClient(
        private val inner: GitClient,
        private val shouldThrow: (java.io.File) -> Boolean,
    ) : GitClient by inner {
        override fun checkoutNewBranch(workDir: java.io.File, branch: String) =
            if (shouldThrow(workDir)) throw RuntimeException("simulated crash on ${workDir.name}")
            else inner.checkoutNewBranch(workDir, branch)

    }

    // ---- Stash + Rollback integration ----------------------------------------

    @Test
    fun `dirty work and stashes are restored after partial failure and rollback`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")
        val subADir = File(root, "SubA")
        // Configure git user on submodule (needed for createBranch commits)
        gitOk(subADir, "config", "user.email", "test@test.com")
        gitOk(subADir, "config", "user.name", "Test")
        createBranch(root, "dev")
        createBranch(subADir, "dev")

        // Make both repos dirty
        File(root, "dirty-main.txt").writeText("main changes\n")
        File(subADir, "dirty-sub.txt").writeText("sub changes\n")

        // SubA has no "no-branch" target → CheckoutStep will fail on SubA
        val preset = Preset("stash-test", "dev", mapOf("SubA" to "no-branch"))
        val opts = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)

        val result = executor.executeResultTest(preset, opts)
        assertFalse("Switch should fail due to missing branch on SubA", result.ok)

        // Rollback
        val rollbackOk = recovery(root).rollback(result)
        assertTrue("Rollback should succeed", rollbackOk)

        // Both repos back on original branch
        assertEquals("main", git.currentBranch(root))
        assertEquals("SubA should be back on main after rollback", "main", git.currentBranch(subADir))

        assertTrue("Main dirty file should be restored", File(root, "dirty-main.txt").exists())
        assertTrue("SubA dirty file should be restored", File(subADir, "dirty-sub.txt").exists())
        for (dir in listOf(root, subADir)) {
            val (_, stashList) = runGit(dir, "stash", "list")
            assertTrue("Recovery stash should be retained in ${dir.name}: $stashList", stashList.isNotBlank())
        }
    }

    @Test
    fun `multi-repo dirty stash all restored on successful switch`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        val subB = createRepo(tmpDir, "subB-src")
        addSubmodule(root, subA, "SubA")
        addSubmodule(root, subB, "SubB")
        gitOk(root, "submodule", "update", "--init", "--recursive")
        val subADir = File(root, "SubA")
        val subBDir = File(root, "SubB")
        gitOk(subADir, "config", "user.email", "test@test.com")
        gitOk(subADir, "config", "user.name", "Test")
        gitOk(subBDir, "config", "user.email", "test@test.com")
        gitOk(subBDir, "config", "user.name", "Test")
        createBranch(root, "dev")
        createBranch(subADir, "dev")
        createBranch(subBDir, "dev")

        // Make all three repos dirty
        File(root, "main-work.txt").writeText("main\n")
        File(subADir, "suba-work.txt").writeText("suba\n")
        File(subBDir, "subb-work.txt").writeText("subb\n")

        val preset = Preset("multi-stash", "dev",
            mapOf("SubA" to "dev", "SubB" to "dev"))
        val (ok, _) = runSwitch(root, preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Multi-repo dirty+stash should succeed", ok)

        // All three repos on dev
        assertEquals("dev", git.currentBranch(root))
        assertEquals("dev", git.currentBranch(subADir))
        assertEquals("dev", git.currentBranch(subBDir))

        // All dirty files restored
        assertTrue("Main work file should be restored", File(root, "main-work.txt").exists())
        assertTrue("SubA work file should be restored", File(subADir, "suba-work.txt").exists())
        assertTrue("SubB work file should be restored", File(subBDir, "subb-work.txt").exists())

        // Immutable recovery backups remain available after applying the changes.
        for (dir in listOf(root, subADir, subBDir)) {
            val (_, list) = runGit(dir, "stash", "list")
            assertTrue("Recovery stash should be retained in ${dir.name}: $list", list.isNotBlank())
        }
    }
}
