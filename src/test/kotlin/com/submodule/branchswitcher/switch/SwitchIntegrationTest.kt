
package com.submodule.branchswitcher.switch
import com.submodule.branchswitcher.executeTest

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

    // ---- Full switch: single repo ----

    @Test
    fun `switch main repo to different branch`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")

        val (ok, _) = runSwitch(root, Preset("test", "dev"), SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should succeed", ok)
        assertEquals("dev", git.currentBranch(root))
    }

    @Test
    fun `switch main repo already on target is no-op`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        gitOk(root, "checkout", "dev")
        val headBefore = git.revParseHead(root)

        val (ok, _) = runSwitch(root, Preset("test", "dev"), SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch should succeed", ok)
        assertEquals("dev", git.currentBranch(root))
        assertEquals("HEAD should not change when already on target", headBefore, git.revParseHead(root))
    }

    // ---- Branch not found ----

    @Test
    fun `switch to non-existent branch fails with diagnostics`() {
        val root = createRepo(tmpDir, "project")
        val (ok, logs) = runSwitch(root, Preset("test", "no-such-branch"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertFalse("Switch to missing branch should fail", ok)
        val hasFail = logs.any { it.contains("[fail]") || it.contains("[fatal]") || it.contains("[error]") || it.contains("[warn]") }
        assertTrue("Log should contain failure diagnostic, got: $logs", hasFail)
    }

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
    fun `dirty stash stashes and pops on checkout`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        File(root, "dirty.txt").writeText("unstaged change\n")

        val (ok, _) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Dirty+Stash switch should succeed", ok)
        assertEquals("dev", git.currentBranch(root))
        assertTrue("Stashed file should be restored by stash pop", File(root, "dirty.txt").exists())
        // Verify stash list is empty
        val (_, stashList) = runGit(root, "stash", "list")
        assertTrue("Stash should be empty after pop, got: $stashList", stashList.isBlank())
    }

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
    fun `submodule init initializes missing submodule directory`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        createBranch(subA, "release")
        addSubmodule(root, subA, "SubA")
        // Remove initialized dir to simulate missing
        val subDir = File(root, "SubA")
        if (subDir.exists()) subDir.deleteRecursively()
        assertFalse("SubA dir should be missing before switch", subDir.exists())

        val preset = Preset("init-test", "main", mapOf("SubA" to "release"))
        val (ok, _) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with submodule init should succeed", ok)
        assertTrue("SubA dir should exist after init", subDir.exists())
        assertTrue("SubA should be a git repo", File(subDir, ".git").exists())
        assertEquals("release", git.currentBranch(subDir))
    }

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

    // ---- Rollback ----

    @Test
    fun `rollback restores original branch after switch`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")

        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)
        executor.executeTest(Preset("test", "dev"), SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertEquals("dev", git.currentBranch(root))

        val rbOk = executor.rollback()
        assertTrue("Rollback should succeed", rbOk)
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `rollback without checkpoint returns false`() {
        val root = createRepo(tmpDir, "project")
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)
        assertFalse("Rollback without checkpoint should return false", executor.rollback())
    }

    // ---- Derive branch ----

    @Test
    fun `derive branch creates same branch on multiple repos`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val deriveBranch = "feature/derived-test"
        gitOk(root, "checkout", "main")
        gitOk(File(root, "SubA"), "checkout", "main")

        git.checkoutNewBranch(root, deriveBranch)
        git.checkoutNewBranch(File(root, "SubA"), deriveBranch)

        assertEquals(deriveBranch, git.currentBranch(root))
        assertEquals(deriveBranch, git.currentBranch(File(root, "SubA")))
    }

    // ---- Sync failure ----

    @Test
    fun `sync step runs and logs result with real submodules`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val (_, logs) = runSwitch(root, Preset("test", "main"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        val hasSyncOk = logs.any { it.contains("submodule sync ok") }
        assertTrue("Log should show sync success with real submodules, got: $logs", hasSyncOk)
    }

    // ---- Fetch ----

    @Test
    fun `switch proceeds even when fetch has no remote`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        // git 2.54+ returns exit 0 for fetch --prune even without a remote configured;
        // the switch proceeds normally since FetchStep succeeds.
        val (ok, _) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true))
        assertTrue("Switch should succeed even with fetchFirst on a repo without remote", ok)
        assertEquals("dev", git.currentBranch(root))
    }

    // ---- Multiple submodules with different branches ----

    @Test
    fun `switch three submodules to three different branches`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        val subB = createRepo(tmpDir, "subB-src")
        val subC = createRepo(tmpDir, "subC-src")
        createBranch(subA, "v1")
        createBranch(subB, "v2")
        createBranch(subC, "v3")
        addSubmodule(root, subA, "A")
        addSubmodule(root, subB, "B")
        addSubmodule(root, subC, "C")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        // Ensure all submodules start on main
        gitOk(File(root, "A"), "checkout", "main")
        gitOk(File(root, "B"), "checkout", "main")
        gitOk(File(root, "C"), "checkout", "main")

        val preset = Preset("three-subs", "main", mapOf("A" to "v1", "B" to "v2", "C" to "v3"))
        val (ok, _) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with 3 submodules should succeed", ok)
        assertEquals("v1", git.currentBranch(File(root, "A")))
        assertEquals("v2", git.currentBranch(File(root, "B")))
        assertEquals("v3", git.currentBranch(File(root, "C")))
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

    // ---- Checkpoint records correct state ----

    @Test
    fun `checkpoint records sha and branch for all repos`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)
        val preset = Preset("ck-test", "main", mapOf("SubA" to "main"))
        executor.executeTest(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

        val checkpoint = executor.getCheckpoint()
        assertNotNull("Checkpoint should exist", checkpoint)
        assertTrue("Checkpoint should contain main repo", checkpoint!!.containsKey("."))
        assertTrue("Checkpoint should contain SubA", checkpoint.containsKey("SubA"))

        val mainSha = checkpoint["."]!!.sha
        assertTrue("SHA should be 40-char hex, got: $mainSha", mainSha.matches(Regex("[0-9a-f]{40}")))
        assertNotNull("Branch should be recorded", checkpoint["."]!!.branch)
    }

    // ---- Stash is fully consumed ----

    @Test
    fun `stash is fully consumed after dirty switch and pop`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        gitOk(root, "checkout", "main")
        File(root, "dirty-work.txt").writeText("work in progress\n")

        val (ok, _) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with stash should succeed", ok)

        val (_, stashList) = runGit(root, "stash", "list")
        assertTrue("Stash list should be empty, got: $stashList", stashList.isBlank())
        assertTrue("Stashed file should exist after pop", File(root, "dirty-work.txt").exists())
        val content = File(root, "dirty-work.txt").readText().replace("\r\n", "\n")
        assertEquals("work in progress\n", content)
    }

    // ---- init 前确认 disabled → auto-inits ----

    @Test
    fun `missing submodule auto-inits when confirmBeforeInit is false`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        val subDir = File(root, "SubA")
        if (subDir.exists()) subDir.deleteRecursively()

        val preset = Preset("auto-init", "main", mapOf("SubA" to "main"))
        val (ok, _) = runSwitch(root, preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = false))
        assertTrue("Switch with auto-init should succeed", ok)
        assertTrue("SubA should be initialized", File(subDir, ".git").exists())
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
    fun `derive skips repos where branch already exists`() {
        val root = createRepo(tmpDir, "project")
        gitOk(root, "checkout", "-b", "feature")
        gitOk(root, "checkout", "main")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main")
        val result = executor.execute(preset, "feature")

        assertFalse("derive must not be allOk when branch exists", result.allOk)
        assertEquals(0, result.actualCreated)
        assertEquals(1, result.branchExists.size)
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `derive with missing submodule blocks all repos`() {
        val root = createRepo(tmpDir, "project")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("ghost" to "main"))
        val result = executor.execute(preset, "feature")

        assertTrue("preflight should block", result.preflightBlocked)
        assertEquals(0, result.actualCreated)
        assertEquals(1, result.skipped.size)
        // Main must NOT be modified
        assertEquals("main", git.currentBranch(root))
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
            override fun localBranchProbe(workDir: java.io.File, branch: String): Boolean? =
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
            override fun dirtyProbe(workDir: java.io.File): Boolean? =
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
    fun `derive preflight blocked does not call checkoutNewBranch`() {
        val root = createRepo(tmpDir, "project")
        gitOk(root, "checkout", "-b", "existing-branch")
        gitOk(root, "checkout", "main")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main")
        val result = executor.execute(preset, "existing-branch")

        assertTrue("preflight should block", result.preflightBlocked)
        assertEquals(1, result.branchExists.size)
        assertEquals(0, result.actualCreated)
        // Verify we never left main
        assertEquals("main", git.currentBranch(root))
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
    fun `derive blocks when main has target branch and submodule is valid`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")
        // Pre-create the target branch on main only
        gitOk(root, "checkout", "-b", "feature")
        gitOk(root, "checkout", "main")

        val executor = DeriveBranchExecutor(root.toPath(), deriveLog(), git)
        val preset = Preset("test", "main", mapOf("SubA" to "main"))
        val result = executor.execute(preset, "feature")

        assertTrue("preflight should block", result.preflightBlocked)
        assertEquals(1, result.branchExists.size)
        assertEquals(0, result.actualCreated)
        // Submodule must NOT be modified
        assertEquals("main", git.currentBranch(File(root, "SubA")))
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
        assertEquals("SubA should be in failed", 1, result.failed.size)
        assertTrue(result.failed["SubA"]!!.contains("simulated crash"))
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

        // need explicit overrides because delegation can't resolve after override
        override fun deleteBranch(workDir: java.io.File, branch: String) = inner.deleteBranch(workDir, branch)
        override fun currentBranch(workDir: java.io.File) = inner.currentBranch(workDir)
        override fun isDirty(workDir: java.io.File) = inner.isDirty(workDir)
        override fun dirtyFileCount(workDir: java.io.File) = inner.dirtyFileCount(workDir)
        override fun stash(workDir: java.io.File, message: String) = inner.stash(workDir, message)
        override fun fetch(workDir: java.io.File) = inner.fetch(workDir)
        override fun localBranchExists(workDir: java.io.File, branch: String) = inner.localBranchExists(workDir, branch)
        override fun remoteBranchExists(workDir: java.io.File, branch: String) = inner.remoteBranchExists(workDir, branch)
        override fun checkoutExisting(workDir: java.io.File, branch: String) = inner.checkoutExisting(workDir, branch)
        override fun checkoutFromRemote(workDir: java.io.File, branch: String) = inner.checkoutFromRemote(workDir, branch)
        override fun pullFf(workDir: java.io.File, branch: String) = inner.pullFf(workDir, branch)
        override fun submoduleSync(gitRoot: java.io.File) = inner.submoduleSync(gitRoot)
        override fun submoduleInitPath(gitRoot: java.io.File, path: String) = inner.submoduleInitPath(gitRoot, path)
        override fun listSubmodulePaths(gitRoot: java.io.File) = inner.listSubmodulePaths(gitRoot)
        override fun listAllBranches(workDir: java.io.File) = inner.listAllBranches(workDir)
        override fun revParseHead(workDir: java.io.File) = inner.revParseHead(workDir)
        override fun stashPop(workDir: java.io.File) = inner.stashPop(workDir)
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

        val ok = executor.executeTest(preset, opts)
        assertFalse("Switch should fail due to missing branch on SubA", ok)

        // Rollback
        val rollbackOk = executor.rollback()
        assertTrue("Rollback should succeed", rollbackOk)

        // Both repos back on original branch
        assertEquals("main", git.currentBranch(root))
        assertEquals("SubA should be back on main after rollback", "main", git.currentBranch(subADir))

        assertTrue("Main dirty file should be restored", File(root, "dirty-main.txt").exists())
        assertTrue("SubA dirty file should be restored", File(subADir, "dirty-sub.txt").exists())
        for (dir in listOf(root, subADir)) {
            val (_, stashList) = runGit(dir, "stash", "list")
            assertTrue("Stash should be empty in ${dir.name}: $stashList", stashList.isBlank())
        }
    }

    @Test
    fun `rollback after checkout failure does not leave orphaned stashes on main`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        File(root, "dirty.txt").writeText("unstaged work\n")

        val preset = Preset("stash-fail", "no-branch")
        val opts = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)

        val ok = executor.executeTest(preset, opts)
        assertFalse("Switch should fail", ok)

        val rollbackOk = executor.rollback()
        assertTrue("Rollback should succeed", rollbackOk)
        assertEquals("main", git.currentBranch(root))

        val (_, stashList) = runGit(root, "stash", "list")
        assertTrue("Stash should be empty after branch-not-found recovery: $stashList", stashList.isBlank())
        assertTrue("Dirty file should exist after rollback", File(root, "dirty.txt").exists())
    }

    @Test
    fun `dirty stash with rollback after branch-not-found restores original state`() {
        val root = createRepo(tmpDir, "project")
        File(root, "dirty.txt").writeText("unstaged work\n")

        // Target branch doesn't exist → CheckoutStep fails → rollback needed
        val preset = Preset("stash-rollback", "no-branch")
        val opts = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)
        val executor = SwitchExecutor(root.toPath(), createStringAppender { log += it }, git)

        val ok = executor.executeTest(preset, opts)
        assertFalse("Switch should fail", ok)

        val rollbackOk = executor.rollback()
        assertTrue("Rollback should succeed", rollbackOk)
        assertEquals("main", git.currentBranch(root))

        // Dirty file should still exist (stash was created before checkout, then popped or restored)
        assertTrue("Dirty file should exist after rollback", File(root, "dirty.txt").exists())
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

        // All stash lists empty
        for (dir in listOf(root, subADir, subBDir)) {
            val (_, list) = runGit(dir, "stash", "list")
            assertTrue("Stash should be empty in ${dir.name}: $list", list.isBlank())
        }
    }
}
