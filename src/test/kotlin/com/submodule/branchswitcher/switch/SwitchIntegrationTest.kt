package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitOps
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
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
class SwitchIntegrationTest {

    private lateinit var tmpDir: Path
    private lateinit var git: GitOps
    private val log = mutableListOf<String>()

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("switch-it-")
        git = GitOps(timeoutSeconds = 30)
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

    /** Execute a switch and return (success, log lines). */
    private fun runSwitch(root: File, preset: Preset, opts: SwitchOptions): Pair<Boolean, List<String>> {
        log.clear()
        val executor = SwitchExecutor(root.toPath(), { log += it }, git)
        val ok = executor.execute(preset, opts)
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
        val hasFail = logs.any { it.contains("[fail]") || it.contains("[fatal]") }
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

        val preset = Preset("multi", "main", mapOf("SubA" to "release", "SubB" to "feature"), pull = false)
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
    fun `dirty skip reports failure but checkout still proceeds`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")
        File(root, "dirty.txt").writeText("changes\n")

        val (ok, logs) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Skip, pull = false, fetchFirst = false))
        // DirtyHandlingStep returns Partial (marks failure) but pipeline continues to CheckoutStep
        assertFalse("Dirty+Skip should report overall failure", ok)
        val hasSkip = logs.any { it.contains("skip") || it.contains("dirty") }
        assertTrue("Log should mention dirty skip, got: $logs", hasSkip)
        // Note: branch may still change because Partial doesn't stop the pipeline
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

        val preset = Preset("init-test", "main", mapOf("SubA" to "release"), pull = false)
        val (ok, _) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertTrue("Switch with submodule init should succeed", ok)
        assertTrue("SubA dir should exist after init", subDir.exists())
        assertTrue("SubA should be a git repo", File(subDir, ".git").exists())
    }

    // ---- Rollback ----

    @Test
    fun `rollback restores original branch after switch`() {
        val root = createRepo(tmpDir, "project")
        createBranch(root, "dev")

        val executor = SwitchExecutor(root.toPath(), { log += it }, git)
        executor.execute(Preset("test", "dev"), SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertEquals("dev", git.currentBranch(root))

        val rbOk = executor.rollback()
        assertTrue("Rollback should succeed", rbOk)
        assertEquals("main", git.currentBranch(root))
    }

    @Test
    fun `rollback without checkpoint returns false`() {
        val root = createRepo(tmpDir, "project")
        val executor = SwitchExecutor(root.toPath(), { log += it }, git)
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
        val (ok, _) = runSwitch(root, Preset("test", "dev"),
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = true))
        assertTrue("Switch should proceed even if fetch warns", ok)
    }

    // ---- Cancel ----

    @Test
    fun `cancelled context reports true when cancelled flag is set`() {
        val root = createRepo(tmpDir, "project")
        val opts = SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false)
        val ctx = SwitchContext(root.toPath(), Preset("test", "dev"), opts, git, { log += it }, cancelled = { true })
        assertTrue("cancelled should be true", ctx.cancelled())

        val ctx2 = SwitchContext(root.toPath(), Preset("test", "dev"), opts, git, { log += it }, cancelled = { false })
        assertFalse("cancelled should be false", ctx2.cancelled())
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

        val preset = Preset("three-subs", "main", mapOf("A" to "v1", "B" to "v2", "C" to "v3"), pull = false)
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

        val preset = Preset("missing-branch", "main", mapOf("SubA" to "no-branch"), pull = false)
        val (ok, logs) = runSwitch(root, preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))
        assertFalse("Switch should fail when submodule branch doesn't exist", ok)
        val hasSubFail = logs.any { it.contains("[fail]") && it.contains("SubA") }
        assertTrue("Log should contain SubA failure, got: $logs", hasSubFail)
    }

    // ---- Checkpoint records correct state ----

    @Test
    fun `checkpoint records sha and branch for all repos`() {
        val root = createRepo(tmpDir, "project")
        val subA = createRepo(tmpDir, "subA-src")
        addSubmodule(root, subA, "SubA")
        gitOk(root, "submodule", "update", "--init", "--recursive")

        val executor = SwitchExecutor(root.toPath(), { log += it }, git)
        val preset = Preset("ck-test", "main", mapOf("SubA" to "main"), pull = false)
        executor.execute(preset, SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false))

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

        val preset = Preset("auto-init", "main", mapOf("SubA" to "main"), pull = false)
        val (ok, _) = runSwitch(root, preset,
            SwitchOptions(DirtyAction.Stash, pull = false, fetchFirst = false, confirmBeforeInit = false))
        assertTrue("Switch with auto-init should succeed", ok)
        assertTrue("SubA should be initialized", File(subDir, ".git").exists())
    }
}
