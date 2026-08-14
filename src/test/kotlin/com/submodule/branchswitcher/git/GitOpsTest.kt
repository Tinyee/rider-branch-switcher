package com.submodule.branchswitcher.git

import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

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
    fun `cancellation terminates descendants before releasing process resources`() {
        val descendantDestroyed = AtomicBoolean(false)
        val descendant = java.lang.reflect.Proxy.newProxyInstance(
            ProcessHandle::class.java.classLoader,
            arrayOf(ProcessHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pid" -> 1234L
                "isAlive" -> !descendantDestroyed.get()
                "destroyForcibly" -> descendantDestroyed.compareAndSet(false, true)
                else -> null
            }
        } as ProcessHandle
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            descendant = descendant,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue(descendantDestroyed.get())
        assertTrue(runningProcess.destroyed)
    }

    @Test
    fun `cooperative exit on SIGTERM avoids a SIGKILL fallback`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            stopAfterDestroy = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue("SIGTERM must be attempted first", runningProcess.gracefulDestroyRequested)
        assertFalse(
            "a cooperative exit must not be force-killed (git removes its own index.lock)",
            runningProcess.forceDestroyRequested,
        )
    }

    @Test
    fun `stubborn process is escalated to SIGKILL after a SIGTERM grace window`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            stopAfterDestroy = false,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue("SIGTERM must be attempted first", runningProcess.gracefulDestroyRequested)
        assertTrue("a stubborn process must be force-killed", runningProcess.forceDestroyRequested)
    }

    @Test
    fun `grace window waits for a live descendant after the parent exits`() {
        val descendantDestroyed = AtomicBoolean(false)
        val forceDestroyAt = AtomicBoolean(false)
        val descendant = java.lang.reflect.Proxy.newProxyInstance(
            ProcessHandle::class.java.classLoader,
            arrayOf(ProcessHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pid" -> 2345L
                "isAlive" -> !descendantDestroyed.get()
                "destroy" -> null
                "destroyForcibly" -> {
                    forceDestroyAt.set(true)
                    descendantDestroyed.set(true)
                    null
                }
                else -> null
            }
        } as ProcessHandle
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            descendant = descendant,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val started = System.nanoTime()
        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue(forceDestroyAt.get())
        assertTrue("descendant must receive the full grace window", elapsedMillis >= 1_000)
    }

    @Test
    fun `windows termination skips the unsupported graceful phase`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(finished = false, onWait = { cancellation.set(true) })
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = false,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertFalse(runningProcess.gracefulDestroyRequested)
        assertTrue(runningProcess.forceDestroyRequested)
    }

    @Test
    fun `process capacity wait is bounded and does not start a command`() {
        var starts = 0
        val runner = GitProcessRunner(
            timeoutSeconds = 1,
            processPermits = Semaphore(0),
        ) {
            starts++
            ControllableProcess(finished = true)
        }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.PROCESS_CAPACITY, result.failureKind)
        assertEquals("process capacity unavailable after 1s", result.stderr)
        assertEquals(0, starts)
    }

    @Test
    fun `unsupported exit future releases capacity only after a stubborn process exits`() {
        val permits = Semaphore(1)
        val cancellation = AtomicBoolean(false)
        val process = ControllableProcess(
            finished = false,
            stopAfterDestroy = false,
            onExitUnsupported = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 1, processPermits = permits) { process }

        val result = runner.run(tmpDir.toFile(), cancellation, "status")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertEquals(0, permits.availablePermits())
        process.completeExit()
        repeat(20) {
            if (permits.availablePermits() == 1) return@repeat
            Thread.sleep(50)
        }
        assertEquals(1, permits.availablePermits())
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

    @Test
    fun `stream capture failure is returned instead of silently losing command output`() {
        val failingStream = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("stream unavailable")
        }
        val process = ControllableProcess(finished = true, stdoutStream = failingStream)
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.OUTPUT_CAPTURE, result.failureKind)
        assertTrue(result.stderr.contains("java.io.IOException: stream unavailable"))
    }

    private class ControllableProcess(
        private val finished: Boolean,
        private val stopAfterDestroy: Boolean = true,
        private val onExitUnsupported: Boolean = false,
        private val interruptOnWait: Boolean = false,
        private val interruptAfterDestroy: Boolean = false,
        private val onWait: (() -> Unit)? = null,
        private val descendant: ProcessHandle? = null,
        private val exitCode: Int = 0,
        stdout: ByteArray = ByteArray(0),
        stderr: ByteArray = ByteArray(0),
        private val stdoutStream: InputStream = ByteArrayInputStream(stdout),
        private val stderrStream: InputStream = ByteArrayInputStream(stderr),
    ) : Process() {
        val waitStarted = CountDownLatch(1)
        @Volatile var destroyed = false
        @Volatile var gracefulDestroyRequested = false
        @Volatile var forceDestroyRequested = false
        @Volatile private var externallyFinished = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdoutStream
        override fun getErrorStream(): InputStream = stderrStream
        override fun waitFor(): Int = exitCode
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitStarted.countDown()
            if (interruptOnWait) throw InterruptedException("test interrupt")
            if (destroyed && interruptAfterDestroy) throw InterruptedException("cleanup interrupt")
            onWait?.invoke()
            return finished || externallyFinished || destroyed && stopAfterDestroy
        }
        override fun exitValue(): Int = exitCode
        override fun isAlive(): Boolean = !(finished || externallyFinished || destroyed && stopAfterDestroy)
        override fun onExit(): CompletableFuture<Process> {
            if (onExitUnsupported) throw UnsupportedOperationException("test process has no onExit")
            return super.onExit()
        }
        override fun destroy() {
            destroyed = true
            gracefulDestroyRequested = true
        }
        override fun destroyForcibly(): Process {
            destroyed = true
            forceDestroyRequested = true
            return this
        }
        override fun descendants(): java.util.stream.Stream<ProcessHandle> =
            if (descendant == null) java.util.stream.Stream.empty() else java.util.stream.Stream.of(descendant)

        fun completeExit() {
            externallyFinished = true
        }
    }

    private fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.waitFor())
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
