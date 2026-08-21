package com.submodule.branchswitcher.git.impl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared fixtures and process doubles for the [GitOps] test classes: a real `git init`
 * temp directory, the default client, and controllable process/stream doubles.
 */
abstract class GitOpsTestBase {

    protected lateinit var tmpDir: Path
    protected lateinit var git: GitOps

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("gitops-test-")
        git = GitOps(timeoutSeconds = 10)
    }

    @After
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    protected fun writeGitmodules(content: String): File {
        val f = tmpDir.resolve(".gitmodules").toFile()
        f.writeText(content)
        return tmpDir.toFile()
    }

    protected fun runGit(directory: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("git ${args.joinToString(" ")}: $output", 0, process.waitFor())
    }

    protected open class ControllableProcess(
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

    protected class RecordingInputStream(
        private val delegate: InputStream,
        private val threadName: AtomicReference<String>,
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
