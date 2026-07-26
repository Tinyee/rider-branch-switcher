package com.submodule.branchswitcher.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class ProjectGitRootTest {

    @Test
    fun `resolves repository root from a nested project directory`() {
        val root = Files.createTempDirectory("git-root")
        Files.createDirectory(root.resolve(".git"))
        val nested = Files.createDirectories(root.resolve("modules/app"))

        assertEquals(root, resolveGitRoot(nested))
    }

    @Test
    fun `recognizes worktree git file and rejects non repository directory`() {
        val worktree = Files.createTempDirectory("git-worktree")
        Files.writeString(worktree.resolve(".git"), "gitdir: ../main/.git/worktrees/test")
        assertEquals(worktree, resolveGitRoot(worktree))

        assertNull(resolveGitRoot(Files.createTempDirectory("not-git")))
    }
}
