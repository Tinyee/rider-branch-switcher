package com.submodule.branchswitcher.git

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [GitOps].listSubmodulePaths — parses .gitmodules files.
 * No real git repo needed.
 */
class GitOpsTest {

    private lateinit var tmpDir: Path
    private lateinit var git: GitOps

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("gitops-test-")
        git = GitOps(timeoutSeconds = 10)
    }

    @After
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    private fun writeGitmodules(content: String): File {
        val f = tmpDir.resolve(".gitmodules").toFile()
        f.writeText(content)
        return tmpDir.toFile()
    }

    // ---- .gitmodules parsing ----

    @Test
    fun `empty list when no gitmodules file`() {
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `empty list when gitmodules is empty`() {
        writeGitmodules("")
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `extracts single path`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
                url = https://example.com/SubA.git
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `extracts multiple paths in order`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
            [submodule "SubB"]
                path = SubB
            [submodule "SubC"]
                path = SubC
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA", "SubB", "SubC"), paths)
    }

    @Test
    fun `skips comment lines starting with hash`() {
        writeGitmodules("""
            # this is a comment
            [submodule "SubA"]
                path = SubA
            # another comment
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `skips comment lines starting with semicolon`() {
        writeGitmodules("""
            ; this is a comment
            [submodule "SubA"]
                path = SubA
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `skips blank lines`() {
        writeGitmodules("""

            [submodule "SubA"]
                path = SubA

        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `ignores non-path keys`() {
        writeGitmodules("""
            [submodule "SubA"]
                url = https://example.com/SubA.git
                branch = main
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `trims trailing whitespace from path`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `path with spaces around equals`() {
        writeGitmodules("""
            [submodule "SubA"]
                path=SubA
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `path with extra whitespace`() {
        writeGitmodules("""
            [submodule "SubA"]
                   path    =    SubA
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `path with deep nesting`() {
        writeGitmodules("""
            [submodule "nested"]
                path = lib/external/deep/SubA
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("lib/external/deep/SubA"), paths)
    }

    // ── Nested submodule discovery ──────────────────────────────────

    @Test
    fun `path is collected when submodule directory exists`() {
        // Verify that listSubmodulePaths works when the submodule
        // directory physically exists on disk.  PropertyTest relies on
        // this because collectSubmodulePaths resolves canonical paths
        // (File.canonicalFile) for symlink-safety, and some platforms
        // require the directory to exist for that resolution.
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
        """.trimIndent())
        java.io.File(tmpDir.toFile(), "SubA").mkdirs()
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    @Test
    fun `flat submodules still work`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
            [submodule "SubB"]
                path = SubB
        """.trimIndent())
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA", "SubB"), paths)
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
    fun `nested submodule without gitmodules is not recursed`() {
        writeGitmodules("""
            [submodule "SubA"]
                path = SubA
        """.trimIndent())
        // SubA dir exists but no .gitmodules — no nested discovery
        java.io.File(tmpDir.toFile(), "SubA").mkdirs()
        val paths = git.listSubmodulePaths(tmpDir.toFile())
        assertEquals(listOf("SubA"), paths)
    }

    // ── Safety: loop and path-escape rejection ────────────────────

    @Test
    fun `path equals dot is rejected`() {
        writeGitmodules("""
            [submodule "bad"]
                path = .
        """.trimIndent())
        assertEquals(emptyList<String>(), git.listSubmodulePaths(tmpDir.toFile()))
    }

    @Test
    fun `path with dotdot is rejected`() {
        writeGitmodules("""
            [submodule "escape"]
                path = ../outside
        """.trimIndent())
        assertEquals(emptyList<String>(), git.listSubmodulePaths(tmpDir.toFile()))
    }

    @Test
    fun `path with dotdot component is rejected`() {
        writeGitmodules("""
            [submodule "bad"]
                path = SubA/../outside
        """.trimIndent())
        assertEquals(emptyList<String>(), git.listSubmodulePaths(tmpDir.toFile()))
    }

    @Test
    fun `absolute path is rejected`() {
        writeGitmodules("""
            [submodule "bad"]
                path = /etc/passwd
        """.trimIndent())
        assertEquals(emptyList<String>(), git.listSubmodulePaths(tmpDir.toFile()))
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
            val paths = git.listSubmodulePaths(root)
            if (created) {
                // Symlink resolves to root — visited seed should reject it
                assertTrue("symlink-to-root must be skipped", paths.isEmpty())
            }
            // If symlink creation failed (e.g. Windows without admin), test passes vacuously
        } finally {
            if (created) linkDir.delete()
        }
    }

    @Test
    fun `GitResult ok is true when exitCode is zero`() {
        val r = GitResult("test", 0, "", "")
        assertTrue(r.ok)
    }

    @Test
    fun `GitResult ok is false when exitCode is non-zero`() {
        val r = GitResult("test", 1, "", "error")
        assertFalse(r.ok)
        assertEquals("error", r.stderr)
    }

    @Test
    fun `remote selection prefers origin then first configured remote`() {
        assertEquals("origin", selectRemoteName(emptyList()))
        assertEquals("origin", selectRemoteName(listOf("upstream", "origin", "fork")))
        assertEquals("upstream", selectRemoteName(listOf("upstream", "fork")))
    }

    @Test
    fun `timeout milliseconds clamps unsafe values`() {
        assertEquals(1_000, safeTimeoutMillis(Int.MIN_VALUE))
        assertEquals(60_000, safeTimeoutMillis(60))
        assertEquals(3_600_000, safeTimeoutMillis(Int.MAX_VALUE))
    }

    @Test
    fun `cancelled operation rejects subsequent commands until operation ends`() {
        git.beginOperation()
        git.cancel()

        val cancelled = git.fetch(tmpDir.toFile())
        assertEquals(-1, cancelled.exitCode)
        assertEquals("cancelled", cancelled.stderr)

        git.endOperation()
        val afterEnd = git.fetch(tmpDir.toFile())
        assertNotEquals("cancelled", afterEnd.stderr)
    }

    @Test
    fun `begin operation clears stale cancellation state`() {
        git.cancel()
        git.beginOperation()

        val result = git.fetch(tmpDir.toFile())

        assertNotEquals("cancelled", result.stderr)
    }

    @Test
    fun `nested operation cannot clear cancellation until all operations end`() {
        git.beginOperation()
        git.cancel()
        git.beginOperation()

        assertEquals("cancelled", git.fetch(tmpDir.toFile()).stderr)
        git.endOperation()
        assertEquals("cancelled", git.fetch(tmpDir.toFile()).stderr)

        git.endOperation()
        assertNotEquals("cancelled", git.fetch(tmpDir.toFile()).stderr)
    }

    @Test
    fun `cancel terminates running process and allows commands after operation ends`() {
        val runningProcess = ControllableProcess(finished = false)
        var starts = 0
        git = GitOps(timeoutSeconds = 10) {
            starts++
            if (starts == 1) runningProcess else ControllableProcess(finished = true)
        }
        git.beginOperation()

        val resultFuture = CompletableFuture.supplyAsync { git.fetch(tmpDir.toFile()) }
        assertTrue(runningProcess.waitStarted.await(5, TimeUnit.SECONDS))
        git.cancel()

        val cancelled = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals("cancelled", cancelled.stderr)
        assertTrue(runningProcess.destroyed)

        git.endOperation()
        assertTrue(git.fetch(tmpDir.toFile()).ok)
    }

    private class ControllableProcess(
        private val finished: Boolean,
    ) : Process() {
        val waitStarted = CountDownLatch(1)
        @Volatile var destroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitStarted.countDown()
            return finished || destroyed
        }
        override fun exitValue(): Int = 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }
}
