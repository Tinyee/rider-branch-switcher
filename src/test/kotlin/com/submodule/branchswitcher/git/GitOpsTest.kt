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
import java.util.concurrent.atomic.AtomicBoolean

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
    fun `comments and blank lines do not produce paths`() {
        writeGitmodules("""
            # path = IgnoredHash

            [submodule "SubA"]
                path = SubA

            ; path = IgnoredSemicolon
        """.trimIndent())
        assertEquals(listOf("SubA"), git.listSubmodulePaths(tmpDir.toFile()))
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
    fun `remote selection prefers origin then first configured remote`() {
        assertEquals("origin", selectRemoteName(emptyList()))
        assertEquals("origin", selectRemoteName(listOf("upstream", "origin", "fork")))
        assertEquals("upstream", selectRemoteName(listOf("upstream", "fork")))
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
    fun `cancelled session rejects its subsequent commands until closed`() {
        val operation = git.openOperation()
        operation.cancel()

        val cancelled = operation.fetch(tmpDir.toFile())
        assertEquals(-1, cancelled.exitCode)
        assertEquals("cancelled", cancelled.stderr)

        operation.close()
        val afterEnd = git.fetch(tmpDir.toFile())
        assertNotEquals("cancelled", afterEnd.stderr)
    }

    @Test
    fun `cancelling one session does not affect another session`() {
        val cancelledOperation = git.openOperation()
        val activeOperation = git.openOperation()
        cancelledOperation.cancel()

        assertEquals("cancelled", cancelledOperation.fetch(tmpDir.toFile()).stderr)
        assertNotEquals("cancelled", activeOperation.fetch(tmpDir.toFile()).stderr)

        cancelledOperation.close()
        activeOperation.close()
    }

    @Test
    fun `session cancel terminates running process and leaves direct commands available`() {
        val runningProcess = ControllableProcess(finished = false)
        var starts = 0
        git = GitOps(timeoutSeconds = 10) {
            starts++
            if (starts == 1) runningProcess else ControllableProcess(finished = true)
        }
        val operation = git.openOperation()

        val resultFuture = CompletableFuture.supplyAsync { operation.fetch(tmpDir.toFile()) }
        assertTrue(runningProcess.waitStarted.await(5, TimeUnit.SECONDS))
        operation.cancel()

        val cancelled = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals("cancelled", cancelled.stderr)
        assertTrue(runningProcess.destroyed)

        operation.close()
        assertTrue(git.fetch(tmpDir.toFile()).ok)
    }

    @Test
    fun `thread interruption terminates process and remains visible to caller`() {
        val runningProcess = ControllableProcess(finished = false, interruptOnWait = true)
        git = GitOps(timeoutSeconds = 10) { runningProcess }

        try {
            val result = git.fetch(tmpDir.toFile())

            assertEquals(GitFailureKind.INTERRUPTED, result.failureKind)
            assertTrue(runningProcess.destroyed)
            assertTrue("runner must preserve the interruption signal", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interruption during cancellation cleanup remains visible to caller`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            interruptAfterDestroy = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { runningProcess }

        try {
            val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

            assertEquals(GitFailureKind.INTERRUPTED, result.failureKind)
            assertTrue(runningProcess.destroyed)
            assertTrue("cleanup must preserve the interruption signal", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `stdout above the capture limit fails instead of returning partial data`() {
        val oversized = ByteArray(GIT_STDOUT_LIMIT_BYTES + 1) { 'x'.code.toByte() }
        val process = ControllableProcess(finished = true, stdout = oversized)
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.OUTPUT_LIMIT, result.failureKind)
        assertTrue(result.stdout.isEmpty())
        assertTrue(result.stderr.contains("stdout exceeded"))
    }

    @Test
    fun `stderr retains a bounded tail on dedicated drain threads`() {
        val suffix = "diagnostic-tail"
        val oversized = ("x".repeat(GIT_STDERR_TAIL_BYTES) + suffix).toByteArray()
        val stdoutThread = java.util.concurrent.atomic.AtomicReference<String>()
        val stderrThread = java.util.concurrent.atomic.AtomicReference<String>()
        val process = ControllableProcess(
            finished = true,
            exitCode = 1,
            stdoutStream = RecordingInputStream("ok".byteInputStream(), stdoutThread),
            stderrStream = RecordingInputStream(oversized.inputStream(), stderrThread),
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "fetch")

        assertEquals(GitFailureKind.GIT_FAILED, result.failureKind)
        assertTrue(result.stderr.startsWith("[stderr truncated"))
        assertTrue(result.stderr.endsWith(suffix))
        assertTrue(stdoutThread.get().startsWith(GIT_DRAIN_THREAD_PREFIX))
        assertTrue(stderrThread.get().startsWith(GIT_DRAIN_THREAD_PREFIX))
    }

    private class ControllableProcess(
        private val finished: Boolean,
        private val interruptOnWait: Boolean = false,
        private val interruptAfterDestroy: Boolean = false,
        private val onWait: (() -> Unit)? = null,
        private val exitCode: Int = 0,
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout),
        private val stderrStream: InputStream = ByteArrayInputStream(stderr),
    ) : Process() {
        val waitStarted = CountDownLatch(1)
        @Volatile var destroyed = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitStarted.countDown()
            if (interruptOnWait) throw InterruptedException("test interrupt")
            if (destroyed && interruptAfterDestroy) throw InterruptedException("cleanup interrupt")
            onWait?.invoke()
            return finished || destroyed
        }
        override fun exitValue(): Int = exitCode
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    private class RecordingInputStream(
        private val delegate: InputStream,
        private val threadName: java.util.concurrent.atomic.AtomicReference<String>,
    ) : InputStream() {
        override fun read(): Int {
            threadName.compareAndSet(null, Thread.currentThread().name)
            return delegate.read()
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            threadName.compareAndSet(null, Thread.currentThread().name)
            return delegate.read(bytes, offset, length)
        }

        override fun close() = delegate.close()
    }
}
