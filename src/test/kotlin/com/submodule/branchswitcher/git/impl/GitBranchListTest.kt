package com.submodule.branchswitcher.git.impl

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Real-git tests for [GitCommandClient.listAllBranches]: namespace-aware ref parsing so legal
 * HEAD-prefixed remote branches survive, the symbolic remote HEAD is dropped, and a local branch
 * whose name starts with the remote is not confused with a remote-tracking branch.
 */
class GitBranchListTest : GitOpsTestBase() {

    @Test
    fun `keeps HEAD-prefixed remote branches and drops the symbolic remote HEAD`() {
        val repo = tmpDir.resolve("branches-head-prefix").toFile().also { it.mkdirs() }
        runGit(repo, "init", "-b", "main")
        runGit(repo, "config", "user.email", "tests@example.com")
        runGit(repo, "config", "user.name", "Branch Switcher Tests")
        File(repo, "tracked.txt").writeText("initial\n")
        runGit(repo, "add", "tracked.txt")
        runGit(repo, "commit", "--quiet", "-m", "initial")
        runGit(repo, "remote", "add", "origin", ".")
        runGit(repo, "update-ref", "refs/remotes/origin/dev", "HEAD")
        runGit(repo, "update-ref", "refs/remotes/origin/HEAD-feature", "HEAD")
        // The symbolic default remote HEAD is not a branch and must not surface. HEAD-feature is a
        // legal remote-tracking branch and must survive the fix that once dropped everything
        // starting with "origin/HEAD".
        runGit(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/dev")

        assertEquals(listOf("HEAD-feature", "dev", "main"), git.listAllBranches(repo))
    }

    @Test
    fun `keeps a remote branch under a HEAD-slash path`() {
        val repo = tmpDir.resolve("branches-head-slash").toFile().also { it.mkdirs() }
        runGit(repo, "init", "-b", "main")
        runGit(repo, "config", "user.email", "tests@example.com")
        runGit(repo, "config", "user.name", "Branch Switcher Tests")
        File(repo, "tracked.txt").writeText("initial\n")
        runGit(repo, "add", "tracked.txt")
        runGit(repo, "commit", "--quiet", "-m", "initial")
        runGit(repo, "remote", "add", "origin", ".")
        runGit(repo, "update-ref", "refs/remotes/origin/HEAD/foo", "HEAD")
        runGit(repo, "update-ref", "refs/remotes/origin/dev", "HEAD")

        // A branch named HEAD/foo cannot coexist with a symbolic origin/HEAD in git's ref
        // namespace, so this repo has none; the branch must not be mistaken for the symbolic
        // HEAD and dropped.
        assertEquals(listOf("HEAD/foo", "dev", "main"), git.listAllBranches(repo))
    }

    @Test
    fun `distinguishes a local branch named after the remote from a tracking branch`() {
        val repo = tmpDir.resolve("branches-local-remote").toFile().also { it.mkdirs() }
        runGit(repo, "init", "-b", "main")
        runGit(repo, "config", "user.email", "tests@example.com")
        runGit(repo, "config", "user.name", "Branch Switcher Tests")
        File(repo, "tracked.txt").writeText("initial\n")
        runGit(repo, "add", "tracked.txt")
        runGit(repo, "commit", "--quiet", "-m", "initial")
        runGit(repo, "remote", "add", "origin", ".")
        runGit(repo, "update-ref", "refs/remotes/origin/dev", "HEAD")
        // A local branch literally named origin/local-only keeps its full name — it is not a
        // remote-tracking branch and must not be collapsed under the remote prefix.
        runGit(repo, "update-ref", "refs/heads/origin/local-only", "HEAD")

        assertEquals(listOf("dev", "main", "origin/local-only"), git.listAllBranches(repo))
    }
}
