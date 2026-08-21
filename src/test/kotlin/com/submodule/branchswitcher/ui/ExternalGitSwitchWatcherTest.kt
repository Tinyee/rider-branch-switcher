package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExternalGitSwitchWatcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reflog path resolves a regular git directory`() {
        val root = temp.newFolder("root")
        val logs = File(File(root, ".git"), "logs").apply { mkdirs() }
        val head = File(logs, "HEAD").apply { writeText("") }

        assertEquals(head, mainReflogPath(root.toPath()))
    }

    @Test
    fun `reflog path resolves a worktree gitdir file`() {
        val root = temp.newFolder("root")
        val gitDir = temp.newFolder("gitdir")
        val logs = File(gitDir, "logs").apply { mkdirs() }
        val head = File(logs, "HEAD").apply { writeText("") }
        File(root, ".git").writeText("gitdir: $gitDir")

        assertEquals(head, mainReflogPath(root.toPath()))
    }

    @Test
    fun `reflog path is null when git metadata is missing or unreadable`() {
        val root = temp.newFolder("root")
        assertNull(mainReflogPath(root.toPath()))

        File(root, ".git").writeText("not a gitdir line")
        assertNull(mainReflogPath(root.toPath()))
    }

    @Test
    fun `reflog stamp update fires only when the stamp moved`() {
        var fires = 0
        fun updated(last: Long, next: Long) = reflogStampUpdate(last, next) { fires++ }

        // First poll initializes the remembered stamp without firing.
        assertEquals(5L, updated(-1L, 5L))
        // Unchanged stamp stays quiet.
        assertEquals(5L, updated(5L, 5L))
        // A moved reflog fires exactly once.
        assertEquals(9L, updated(5L, 9L))
        assertEquals(1, fires)
    }

    @Test
    fun `reflog stamp failing to read is still a change and fires`() {
        var fires = 0
        fun updated(last: Long, next: Long) = reflogStampUpdate(last, next) { fires++ }

        // First poll initializes.
        assertEquals(7L, updated(-1L, 7L))
        // A failed lastModified read surfaces as -1; it is a stamp change (the poll
        // refreshes rather than silently staying on the old stamp).
        assertEquals(-1L, updated(7L, -1L))
        assertEquals(1, fires)
    }
}
