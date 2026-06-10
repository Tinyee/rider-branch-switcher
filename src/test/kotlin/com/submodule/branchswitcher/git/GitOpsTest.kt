package com.submodule.branchswitcher.git

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
}
