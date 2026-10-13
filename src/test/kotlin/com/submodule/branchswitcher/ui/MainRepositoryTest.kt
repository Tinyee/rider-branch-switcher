package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.log.createStringAppender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Pins what the module owns: resolving the main repository, and reporting when there is
 * none. Before it existed, six entry points restated that report and had begun to drift —
 * one of them resolved the root directly and never emitted the worktree note.
 *
 * The failure is observed through the injected `reportMissingRoot`, because the platform's
 * real notification is a global singleton that a unit test cannot reach.
 */
class MainRepositoryTest {

    private val reports = mutableListOf<Unit>()
    private val logs = mutableListOf<String>()

    private fun repository(basePath: String?) = MainRepository(
        basePath = { basePath },
        log = createStringAppender { logs += it },
        reportMissingRoot = { reports += Unit },
    )

    @Test
    fun `resolves the repository root from a nested project directory`() {
        val root = Files.createTempDirectory("main-repo")
        Files.createDirectory(root.resolve(".git"))
        val nested = Files.createDirectories(root.resolve("modules/app"))

        assertEquals(root, repository(nested.toString()).resolve())
        assertEquals("a resolvable root must not report a failure", 0, reports.size)
    }

    @Test
    fun `resolve stays silent when there is no repository`() {
        // Callers that treat an absent root as "nothing to do" — a file-status filter, a
        // state probe — must not raise a notification the user never asked for.
        assertNull(repository(Files.createTempDirectory("not-git").toString()).resolve())
        assertNull("a project with no base path has no repository either", repository(null).resolve())
        assertEquals("resolve must never report", 0, reports.size)
    }

    @Test
    fun `resolveOrReport reports exactly once per failed call`() {
        val repo = repository(Files.createTempDirectory("not-git").toString())

        assertNull(repo.resolveOrReport())
        assertNull(repo.resolveOrReport())

        assertEquals("each failed call reports once", 2, reports.size)
        assertTrue(
            "the log must name the failure entry points used to restate",
            logs.any { it.contains(MainRepository.MISSING_ROOT_LOG) },
        )
    }

    @Test
    fun `resolveOrReport is silent when the root resolves`() {
        val root = Files.createTempDirectory("main-repo-ok")
        Files.createDirectory(root.resolve(".git"))

        assertEquals(root, repository(root.toString()).resolveOrReport())
        assertEquals("a successful resolve must not report", 0, reports.size)
    }

    @Test
    fun `the log line names the project directory but never its absolute path`() {
        // The log panel is copyable and shareable, so the user's filesystem layout must
        // not travel in it — only the project directory's own name.
        val parent = Files.createTempDirectory("layout-secret")
        val project = Files.createDirectories(parent.resolve("my-project"))

        repository(project.toString()).resolve()

        val line = logs.single { it.contains("git root not resolved") }
        assertTrue("the project directory name is useful for diagnosis", line.contains("my-project"))
        assertTrue("the absolute layout must not reach the copyable log", !line.contains(parent.toString()))
    }

    @Test
    fun `the worktree note is emitted once, not once per resolve`() {
        // A worktree's `.git` is a file, not a directory. It is worth naming so a later
        // reader does not chase it, but it describes the project layout, which does not
        // change while the project is open.
        val worktree = Files.createTempDirectory("worktree")
        Files.writeString(worktree.resolve(".git"), "gitdir: ../main/.git/worktrees/test")
        val repo = repository(worktree.toString())

        repeat(3) { repo.resolve() }

        assertEquals(
            "the worktree note describes the project, so it is said once",
            1,
            logs.count { it.contains("detected git worktree") },
        )
    }

    @Test
    fun `a worktree resolves to its own directory`() {
        val worktree = Files.createTempDirectory("worktree-ok")
        Files.writeString(worktree.resolve(".git"), "gitdir: ../main/.git/worktrees/test")

        assertEquals(worktree, repository(worktree.toString()).resolve())
    }
}
