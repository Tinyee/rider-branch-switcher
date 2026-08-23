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
    fun `targetBranchMatches reports structural collisions exact ancestor and descendant`() {
        val repo = tmpDir.resolve("repo").toFile().also { it.mkdirs() }
        runGit(repo, "init", "-b", "main")
        File(repo, "a.txt").writeText("a")
        File(repo, "dir/b.txt").apply { parentFile.mkdirs() }.writeText("b")
        File(repo, "dir/sub/c.txt").apply { parentFile.mkdirs() }.writeText("c")
        runGit(repo, "add", "-A")
        runGit(repo, "commit", "-m", "init")

        // Exact match: the untracked file is itself tracked on the target.
        assertEquals(listOf("a.txt"), git.targetBranchMatches(repo, "main", listOf("a.txt")))
        // Tracked descendant: an untracked FILE at `dir` blocks the target's tracked dir/ tree.
        assertEquals(listOf("dir"), git.targetBranchMatches(repo, "main", listOf("dir")))
        // Tracked ancestor as a file: a path under the tracked FILE `dir/b.txt`.
        assertEquals(listOf("dir/b.txt/x"), git.targetBranchMatches(repo, "main", listOf("dir/b.txt/x")))
        // An untracked file inside a tracked directory the target does NOT track is not a collision.
        assertEquals(emptyList<String>(), git.targetBranchMatches(repo, "main", listOf("dir/other.txt")))
        // A missing path is not a collision.
        assertEquals(emptyList<String>(), git.targetBranchMatches(repo, "main", listOf("missing.txt")))
        // The drop-authorization query against the actual HEAD mirrors the same rules.
        assertEquals(listOf("a.txt"), git.headStructuralCollisions(repo, listOf("a.txt")))
        assertEquals(listOf("dir"), git.headStructuralCollisions(repo, listOf("dir")))
        assertEquals(emptyList<String>(), git.headStructuralCollisions(repo, listOf("dir/other.txt")))
    }

    @Test
    fun `untracked and target-tree queries survive odd file names with tabs and quotes`() {
        val repo = tmpDir.resolve("repo").toFile().also { it.mkdirs() }
        runGit(repo, "init", "-b", "main")
        val trackedTab = "a\tb.txt"
        File(repo, trackedTab).writeText("tracked")
        runGit(repo, "add", "-A")
        runGit(repo, "commit", "-m", "init")
        val untrackedTab = "odd\tname.txt"
        File(repo, untrackedTab).writeText("untracked")
        val leadingSpace = " leading.txt"
        File(repo, leadingSpace).writeText("untracked")

        val untracked = git.untrackedFiles(repo)
        // -z keeps the raw path; the C-quoted form ("odd\tname.txt") must never leak.
        assertTrue("a tab filename must come back raw", untrackedTab in untracked)
        assertTrue("a leading-space filename must keep its leading space", leadingSpace in untracked)
        assertFalse("the C-style escape must not leak into the results", untracked.any { it.contains("\\t") })

        // The raw tab path must match as a collision against the tracked tree and HEAD.
        assertEquals(
            "a tracked tab filename must collide by its raw path",
            listOf(trackedTab),
            git.targetBranchMatches(repo, "main", listOf(trackedTab)),
        )
        assertEquals(
            "the raw tab path must also collide against the actual HEAD",
            listOf(trackedTab),
            git.headStructuralCollisions(repo, listOf(trackedTab)),
        )
    }

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

    @Test
    fun `stash list query failure is not mistaken for a missing stash`() {
        val failingStashGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(finished = true, exitCode = 1, stderr = "fatal: bad config\n".toByteArray())
        }

        val failure = assertThrows(GitQueryException::class.java) {
            failingStashGit.stashOidByMessage(tmpDir.toFile(), "branch-switcher: before -> ")
        }
        assertEquals("a non-zero stash list is a query failure", GitFailureKind.GIT_FAILED, failure.result.failureKind)
    }

    @Test
    fun `empty stash list returns no match without throwing`() {
        val emptyStashGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(finished = true, stdout = ByteArray(0))
        }

        assertNull(emptyStashGit.stashOidByMessage(tmpDir.toFile(), "branch-switcher: before -> "))
    }

    @Test
    fun `stashDrop resolves the oid to its current selector and never drops by bare oid`() {
        val oid = "abc123abc123"
        val dropCommands = mutableListOf<List<String>>()
        val dropGit = GitOps(timeoutSeconds = 10) { builder ->
            val args = builder.command()
            if (args.contains("list")) {
                ControllableProcess(
                    finished = true,
                    stdout = "stash@{1}\t$oid\tOn main: some stash\n".toByteArray(),
                )
            } else {
                dropCommands += args
                ControllableProcess(finished = true)
            }
        }

        val result = dropGit.stashDrop(tmpDir.toFile(), oid)

        assertTrue("the drop must succeed: ${result.diagnostic()}", result.ok)
        val dropArgs = dropCommands.single()
        assertTrue("the drop must target the resolved selector, got: $dropArgs", dropArgs.contains("stash@{1}"))
        assertFalse("the drop must never pass the bare oid, got: $dropArgs", dropArgs.contains(oid))
    }

    @Test
    fun `stashDrop of an already-gone oid is a no-op success`() {
        val dropCommands = mutableListOf<List<String>>()
        val emptyGit = GitOps(timeoutSeconds = 10) { builder ->
            if (builder.command().contains("list")) {
                ControllableProcess(finished = true, stdout = ByteArray(0))
            } else {
                dropCommands += builder.command()
                ControllableProcess(finished = true)
            }
        }

        val result = emptyGit.stashDrop(tmpDir.toFile(), "deadbeef")

        assertTrue("an already-dropped stash must be a no-op success", result.ok)
        assertTrue("no drop command may run for a missing oid", dropCommands.isEmpty())
    }

    @Test
    fun `stashDrop with a failing stash list query never drops`() {
        val dropCommands = mutableListOf<List<String>>()
        val failingGit = GitOps(timeoutSeconds = 10) { builder ->
            if (builder.command().contains("list")) {
                ControllableProcess(finished = true, exitCode = 1, stderr = "fatal: bad config\n".toByteArray())
            } else {
                dropCommands += builder.command()
                ControllableProcess(finished = true)
            }
        }

        val result = failingGit.stashDrop(tmpDir.toFile(), "abc123")

        assertFalse("a failing stash list must fail the drop: ${result.diagnostic()}", result.ok)
        assertEquals(GitFailureKind.GIT_FAILED, result.failureKind)
        assertTrue("no drop command may run after a list failure", dropCommands.isEmpty())
    }

    @Test
    fun `stashDrop with malformed stash list output fails closed and never drops`() {
        val dropCommands = mutableListOf<List<String>>()
        val malformedGit = GitOps(timeoutSeconds = 10) { builder ->
            if (builder.command().contains("list")) {
                // One tab separator only — a valid row needs selector, oid, and subject.
                ControllableProcess(finished = true, stdout = "stash@{0}\tbroken-oid\n".toByteArray())
            } else {
                dropCommands += builder.command()
                ControllableProcess(finished = true)
            }
        }

        val result = malformedGit.stashDrop(tmpDir.toFile(), "anything")

        assertFalse("malformed output must fail closed, not look like an already-gone stash: ${result.diagnostic()}", result.ok)
        assertTrue("no drop command may run on malformed output", dropCommands.isEmpty())
    }

    @Test
    fun `stashDrop re-resolves when an external stash shifts the selector between list calls`() {
        val oid = "0000oooo00"
        val other = "aaaa1111aa"
        val newer = "bbbb2222bb"
        // attempt 0 resolve: oid sits at stash@{1}; attempt 0 confirm: an external stash was
        // pushed, so stash@{1} now maps to `other` and oid moved to stash@{2}; attempt 1
        // resolve + confirm: stable, so the drop must target the re-resolved stash@{2}.
        val responses = listOf(
            "stash@{0}\t$other\tOn main: other\nstash@{1}\t$oid\tOn main: oid\n",
            "stash@{0}\t$newer\tOn main: newer\nstash@{1}\t$other\tOn main: other\nstash@{2}\t$oid\tOn main: oid\n",
            "stash@{0}\t$newer\tOn main: newer\nstash@{1}\t$other\tOn main: other\nstash@{2}\t$oid\tOn main: oid\n",
            "stash@{0}\t$newer\tOn main: newer\nstash@{1}\t$other\tOn main: other\nstash@{2}\t$oid\tOn main: oid\n",
        )
        val dropCommands = mutableListOf<List<String>>()
        var call = 0
        val shiftingGit = GitOps(timeoutSeconds = 10) { builder ->
            val args = builder.command()
            if (args.contains("list")) {
                ControllableProcess(finished = true, stdout = responses[call++].toByteArray())
            } else {
                dropCommands += args
                ControllableProcess(finished = true)
            }
        }

        val result = shiftingGit.stashDrop(tmpDir.toFile(), oid)

        assertTrue("the drop must succeed after re-resolution: ${result.diagnostic()}", result.ok)
        assertTrue("the drop must target the re-resolved selector", dropCommands.single().contains("stash@{2}"))
        assertFalse("the drop must not use the stale selector", dropCommands.single().contains("stash@{1}"))
    }

    @Test
    fun `stashDrop drops the entry identified by oid even after a newer stash shifts selectors`() {
        val dir = tmpDir.toFile()
        gitInitWithCommit(dir)
        File(dir, "a.txt").writeText("a")
        runGit(dir, "stash", "push", "-u", "-m", "first")
        val firstOid = gitOutput(dir, "rev-parse", "--verify", "refs/stash")
        File(dir, "b.txt").writeText("b")
        runGit(dir, "stash", "push", "-u", "-m", "second")

        // first's stash is now stash@{1}; dropping by oid must drop first, not the newer second.
        val result = git.stashDrop(dir, firstOid)
        assertTrue("drop by oid must succeed: ${result.diagnostic()}", result.ok)

        val remaining = gitOutput(dir, "stash", "list")
        assertTrue("the second stash must remain: $remaining", remaining.contains("second"))
        assertFalse("the first stash must be gone: $remaining", remaining.contains("first"))
    }

    private fun gitInitWithCommit(dir: File) {
        runGit(dir, "init")
        runGit(dir, "config", "user.email", "test@test.com")
        runGit(dir, "config", "user.name", "Test")
        runGit(dir, "config", "core.autocrlf", "false")
        File(dir, "README.md").writeText("# repo\n")
        runGit(dir, "add", "README.md")
        runGit(dir, "commit", "-m", "initial")
    }

    private fun gitOutput(dir: File, vararg args: String): String {
        val proc = ProcessBuilder(listOf("git") + args).directory(dir).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        assertEquals("git ${args.joinToString(" ")} failed in ${dir.name}", 0, proc.waitFor())
        return out
    }

    @Test
    fun `untracked paths preserve leading and trailing whitespace in names`() {
        val whitespaceGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(
                finished = true,
                // -z output: NUL-delimited raw paths (git disables C-style quoting).
                stdout = " leading.txt\u0000trailing.txt \u0000plain.txt\u0000".toByteArray(),
            )
        }

        assertEquals(
            listOf(" leading.txt", "trailing.txt ", "plain.txt"),
            whitespaceGit.untrackedFiles(tmpDir.toFile()),
        )
    }

    @Test
    fun `last NUL record keeps a trailing space in the file name`() {
        // The generic process layer must not trim trailing whitespace off the LAST raw
        // record: git ls-files -z terminates it with NUL, and a file whose name ends in a
        // space would otherwise lose it (trimEnd() strips the NUL, then the space).
        val trailingSpaceGit = GitOps(timeoutSeconds = 10) { _ ->
            ControllableProcess(
                finished = true,
                stdout = "a.txt\u0000trailing.txt \u0000".toByteArray(),
            )
        }

        assertEquals(
            listOf("a.txt", "trailing.txt "),
            trailingSpaceGit.untrackedFiles(tmpDir.toFile()),
        )
    }
}
