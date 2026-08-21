package com.submodule.branchswitcher.git.impl

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.SubmoduleRegistration
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * [GitOps] parsing and query tests: .gitmodules topology, nested discovery, path
 * safety, remote selection, lock probing, submodule-only dirt, and the atomic
 * HEAD-and-branch read. No process-termination scenarios; those live in
 * [GitOpsProcessTest].
 */
class GitOpsTest : GitOpsTestBase() {

    @Test
    fun `isGitRepo returns true only for usable git repositories`() {
        val plainDir = tmpDir.resolve("plain").toFile().also { it.mkdirs() }
        assertFalse("plain directory is not a git repo", git.isGitRepo(plainDir))

        val repoDir = tmpDir.resolve("repo").toFile().also { it.mkdirs() }
        val proc = ProcessBuilder("git", "init")
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals("git init should succeed: $output", 0, proc.waitFor())

        assertTrue("initialized directory should be a git repo", git.isGitRepo(repoDir))
        val nestedPlainDir = File(repoDir, "SubA").also { it.mkdirs() }
        assertFalse("ordinary child directory must not resolve to its parent repo", git.isGitRepo(nestedPlainDir))
    }

    // ---- .gitmodules parsing ----

    @Test
    fun `empty list when no gitmodules file`() {
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `extracts multiple paths in order`() {
        writeGitmodules("""
            # path = IgnoredHash
            [submodule "SubA"]
                path = SubA
                url = https://example.com/SubA.git
            [submodule "SubB"]
                path = SubB
            [submodule "SubC"]
                path = SubC
                branch = main
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA", "SubB", "SubC"), paths)
    }

    @Test
    fun `declared urls are read from gitmodules`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
                url = https://example.com/SubA.git
            [submodule "SubB"]
                path = SubB
        """.trimIndent())
        assertEquals(
            listOf(
                SubmoduleRegistration("SubA", "SubA", ".", url = "https://example.com/SubA.git"),
                SubmoduleRegistration("SubB", "SubB", ".", url = null),
            ),
            git.registeredSubmodules(tmpDir.toFile()),
        )
    }

    @Test
    fun `path assignment accepts supported whitespace variants`() {
        val cases = listOf(
            "path=SubA",
            "path = SubA",
            "   path    =    SubA",
            "path = SubA   ",
        )
        for (assignment in cases) {
            writeGitmodules("[submodule \"SubA\"]\n$assignment")
            assertEquals("assignment: '$assignment'", listOf("SubA"), git.listSubmodulePaths(tmpDir.toFile()))
        }
    }

    @Test
    fun `git config semantics preserve quoted comment characters and ignore trailing comments`() {
        writeGitmodules(
            """
            [submodule "quoted"]
                path = "folder # one" # trailing comment
            [submodule "plain"]
                path = SubA ; another comment
            """.trimIndent(),
        )

        assertEquals(listOf("folder # one", "SubA"), git.listSubmodulePaths(tmpDir.toFile()))
    }

    @Test(expected = GitQueryException::class)
    fun `malformed gitmodules fails instead of returning a partial topology`() {
        writeGitmodules(
            """
            [submodule "broken]
                path = SubA
            """.trimIndent(),
        )

        git.listSubmodulePaths(tmpDir.toFile())
    }

    // ── Nested submodule discovery ──────────────────────────────────

    @Test
    fun `path is collected even when submodule directory does not exist`() {
        // canonicalFile resolves paths without requiring existence on
        // all tested platforms. The catch-branch (with LOG.warning) is
        // a safety net for exotic filesystem errors (broken symlinks,
        // path-too-long, etc.).
        writeGitmodules("""
            [submodule "GhostDir"]
                path = GhostDir
        """.trimIndent())
        // GhostDir is NOT created — canonicalFile should still succeed
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("GhostDir"), paths)
    }

    @Test
    fun `nested submodules are discovered recursively`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
        """.trimIndent())
        // SubA itself has a .gitmodules with nested subs
        val subADir = java.io.File(tmpDir.toFile(), "SubA")
        subADir.mkdirs()
        java.nio.file.Files.writeString(
            subADir.toPath().resolve(".gitmodules"),
            """
            [submodule "SubA1"]
                path = SubA1
            [submodule "SubA2"]
                path = SubA2
            """.trimIndent()
        )
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA", "SubA/SubA1", "SubA/SubA2"), paths)
        assertEquals(
            listOf(
                SubmoduleRegistration("SubA", "SubA", "."),
                SubmoduleRegistration("SubA/SubA1", "SubA1", "SubA"),
                SubmoduleRegistration("SubA/SubA2", "SubA2", "SubA"),
            ),
            git.registeredSubmodules(tmpDir.toFile()),
        )
    }

    @Test
    fun `nested submodules with deep paths`() {
        writeGitmodules("""
            [submodule "lib"]
                path = lib/common
        """.trimIndent())
        val libDir = java.io.File(tmpDir.toFile(), "lib/common")
        libDir.mkdirs()
        java.nio.file.Files.writeString(
            libDir.toPath().resolve(".gitmodules"),
            """
            [submodule "nested"]
                path = nested/SubX
            """.trimIndent()
        )
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("lib/common", "lib/common/nested/SubX"), paths)
    }

    @Test
    fun `recursion stops at depth 10`() {
        // Build a chain of 12 nested .gitmodules, each pointing one level
        // deeper. Only l1..l11 (11 entries) are discovered; l12 is beyond
        // the depth-10 guard in collectSubmodulePaths.
        val root = tmpDir.toFile()
        var currentDir = root
        for (i in 1..12) {
            val dirName = "l$i"
            java.io.File(currentDir, dirName).mkdirs()
            // Write .gitmodules in currentDir referencing dirName
            java.nio.file.Files.writeString(
                java.io.File(currentDir, ".gitmodules").toPath(),
                """
                [submodule "$dirName"]
                    path = $dirName
                """.trimIndent()
            )
            currentDir = java.io.File(currentDir, dirName)
        }
        val paths = git.listSubmodulePaths(root)
        // l1 through l11 = 11 entries; l12 is cut off at depth 10
        assertEquals(11, paths.size)
        assertEquals("l1", paths[0])
        assertTrue(paths.last().startsWith("l1/l2/"))
    }

    // ── Safety: loop and path-escape rejection ────────────────────

    @Test
    fun `unsafe submodule paths are rejected`() {
        listOf(".", "../outside", "SubA/../outside", "/etc/passwd").forEach { unsafePath ->
            writeGitmodules("[submodule \"bad\"]\npath = $unsafePath")
            assertEquals("path: $unsafePath", emptyList<String>(), git.listSubmodulePaths(tmpDir.toFile()))
        }
    }

    @Test
    fun `symlink to root is rejected via visited guard`() {
        val root = tmpDir.toFile()
        writeGitmodules("""
            [submodule "link"]
                path = link-to-root
        """.trimIndent())
        // Try to create a symlink back to root; skip test cleanly if unsupported
        val linkDir = java.io.File(root, "link-to-root")
        val created = try {
            java.nio.file.Files.createSymbolicLink(linkDir.toPath(), root.toPath())
            true
        } catch (_: Exception) { false }
        try {
            assumeTrue("symbolic links are not available on this platform", created)
            val paths = git.listSubmodulePaths(root)
            assertTrue("symlink-to-root must be skipped", paths.isEmpty())
        } finally {
            if (created) linkDir.delete()
        }
    }

    @Test
    fun `remote selection prefers origin then first configured remote`() {
        assertEquals("origin", selectRemoteName(emptyList()))
        assertEquals("origin", selectRemoteName(listOf("upstream", "origin", "fork")))
        assertEquals("upstream", selectRemoteName(listOf("upstream", "fork")))
    }

    @Test
    fun `remote selection cache is isolated between operation sessions and preflight probes`() {
        val repository = tmpDir.resolve("remote-cache").toFile().also { it.mkdirs() }
        runGit(repository, "init", "--quiet")
        runGit(repository, "config", "user.email", "tests@example.com")
        runGit(repository, "config", "user.name", "Branch Switcher Tests")
        File(repository, "tracked.txt").writeText("initial\n")
        runGit(repository, "add", "tracked.txt")
        runGit(repository, "commit", "--quiet", "-m", "initial")
        runGit(repository, "remote", "add", "origin", ".")
        runGit(repository, "update-ref", "refs/remotes/origin/dev", "HEAD")

        assertTrue("dev" in git.inspectPreflight(repository, setOf("dev")).remoteBranches)
        git.openOperation().use { first ->
            assertTrue(first.remoteBranchExists(repository, "dev"))
        }
        runGit(repository, "remote", "remove", "origin")
        runGit(repository, "remote", "add", "upstream", ".")
        runGit(repository, "update-ref", "refs/remotes/upstream/dev", "HEAD")

        assertTrue("dev" in git.inspectPreflight(repository, setOf("dev")).remoteBranches)
        git.openOperation().use { second ->
            assertTrue(second.remoteBranchExists(repository, "dev"))
        }
    }

    @Test
    fun `timeout seconds clamps unsafe values`() {
        assertEquals(1, safeTimeoutSeconds(Int.MIN_VALUE))
        assertEquals(60, safeTimeoutSeconds(60))
        assertEquals(3_600, safeTimeoutSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `failed git queries throw instead of reporting clean or missing`() {
        val repo = tmpDir.resolve("query-failure").toFile().also {
            it.mkdirs()
            File(it, ".git").mkdir()
        }
        git = GitOps(timeoutSeconds = 10) { throw java.io.IOException("git unavailable") }

        val dirtyFailure = assertThrows(GitQueryException::class.java) { git.isDirty(repo) }
        assertEquals(GitFailureKind.START_FAILED, dirtyFailure.result.failureKind)
        assertThrows(GitQueryException::class.java) { git.currentBranch(repo) }
        assertThrows(GitQueryException::class.java) { git.isGitRepo(repo) }
        assertThrows(GitQueryException::class.java) { git.localBranchExists(repo, "main") }
    }

    @Test
    fun `index lock probe fails closed when process capacity is unavailable`() {
        val runner = GitProcessRunner(
            timeoutSeconds = 1,
            processPermits = Semaphore(0),
            processStarter = { error("process must not start") },
        )
        val client = GitCommandClient(runner, ConcurrentHashMap())

        val failure = assertThrows(GitQueryException::class.java) {
            client.indexLockFile(tmpDir.toFile())
        }

        assertEquals(GitFailureKind.PROCESS_CAPACITY, failure.result.failureKind)
    }

    @Test
    fun `index lock path is checked directly for a normal git directory`() {
        val repository = tmpDir.resolve("direct-lock").toFile().also { it.mkdirs() }
        val gitDirectory = File(repository, ".git").also { it.mkdirs() }
        val lock = File(gitDirectory, "index.lock").also { it.writeText("") }
        var starts = 0
        val directGit = GitOps(timeoutSeconds = 10) { builder ->
            starts++
            builder.start()
        }

        assertEquals(lock.canonicalPath, directGit.indexLockFile(repository))
        assertEquals("direct lock check must not spawn git", 0, starts)
    }

    @Test
    fun `index lock path resolves a worktree gitdir file without spawning git`() {
        val repository = tmpDir.resolve("worktree-lock").toFile().also { it.mkdirs() }
        val gitDir = tmpDir.resolve("worktree-gitdir").toFile().also { it.mkdirs() }
        val lock = File(gitDir, "index.lock").also { it.writeText("") }
        File(repository, ".git").writeText("gitdir: $gitDir")
        var starts = 0
        val directGit = GitOps(timeoutSeconds = 10) { builder ->
            starts++
            builder.start()
        }

        assertEquals(lock.canonicalPath, directGit.indexLockFile(repository))
        assertEquals("worktree lock check must not spawn git", 0, starts)
    }

    @Test
    fun `index lock path falls back to rev-parse when gitdir line is malformed`() {
        val repository = tmpDir.resolve("malformed-worktree").toFile().also { it.mkdirs() }
        // A damaged `.git` file must not be treated as a path; the probe falls back to git.
        File(repository, ".git").writeText("not a gitdir line")
        val lock = File(repository, "index.lock").also { it.writeText("") }
        val commands = mutableListOf<List<String>>()
        val fallbackGit = GitOps(timeoutSeconds = 10) { builder ->
            commands += builder.command()
            ControllableProcess(finished = true, stdout = "index.lock\n".toByteArray())
        }

        assertEquals(lock.canonicalPath, fallbackGit.indexLockFile(repository))
        assertTrue(commands.single().joinToString(" ").contains("rev-parse --git-path index.lock"))
    }

    @Test
    fun `submodule-only status uses bounded untracked enumeration`() {
        val commands = mutableListOf<List<String>>()
        val boundedGit = GitOps(timeoutSeconds = 10) { builder ->
            commands += builder.command()
            ControllableProcess(
                finished = true,
                stdout = "# branch.oid abc123\n? untracked\n".toByteArray(),
            )
        }

        assertFalse(boundedGit.isSubmoduleOnlyDirty(tmpDir.toFile()))

        val command = commands.single().joinToString(" ")
        assertTrue(command.contains("--untracked-files=normal"))
        assertFalse(command.contains("--untracked-files=all"))
    }

    @Test
    fun `submodule-only dirt is recognized from a porcelain status`() {
        val boundedGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(
                finished = true,
                stdout = (
                    "# branch.oid abc123\n" +
                        "# branch.head main\n" +
                        "1 .M S..U 160000 160000 160000 oid1 oid1 Sub\n"
                    ).toByteArray(),
            )
        }

        assertTrue(boundedGit.isSubmoduleOnlyDirty(tmpDir.toFile()))
    }

    @Test
    fun `head and branch are read atomically from a bounded status query`() {
        val commands = mutableListOf<List<String>>()
        val atomicGit = GitOps(timeoutSeconds = 10) { builder ->
            commands += builder.command()
            ControllableProcess(
                finished = true,
                stdout = ("# branch.oid 0123456789abcdef\n" +
                    "# branch.head (detached)\n" +
                    "1 M. N... 100644 100644 100644 abc def file.txt\n").toByteArray(),
            )
        }

        val detached = atomicGit.headAndBranch(tmpDir.toFile())
        assertEquals("0123456789abcdef", detached?.sha)
        assertNull(detached?.branch)

        val command = commands.single().joinToString(" ")
        assertTrue(command.contains("--porcelain=v2"))
        assertTrue(
            "untracked enumeration must stay bounded for a checkpoint read",
            command.contains("--untracked-files=no"),
        )
    }

    @Test
    fun `head and branch report an unborn branch as no sha`() {
        val unbornGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(
                finished = true,
                stdout = "# branch.oid (initial)\n# branch.head main\n".toByteArray(),
            )
        }

        val unborn = unbornGit.headAndBranch(tmpDir.toFile())
        assertEquals("main", unborn?.branch)
        assertNull(unborn?.sha)
    }
}
