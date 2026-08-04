package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy

class SingleRepositorySwitcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `busy result does not open a Git operation`() = runBlocking {
        val git = RecordingGit()
        val switcher = switcher(git, tryAcquireWrite = { null })

        var callbackInvoked = false
        val started = switcher.start(
            this,
            temp.newFolder("root").toPath(),
            "module",
            "dev",
            "Switching",
        ) { callbackInvoked = true }

        assertFalse(started)
        assertFalse(callbackInvoked)
        assertEquals(0, git.openCount)
    }

    @Test
    fun `skip checks preserve not initialized dirty and current branch precedence`() = runBlocking {
        val root = temp.newFolder("root")
        val git = RecordingGit()
        var leaseCloseCount = 0
        val switcher = switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
        )

        val missing = runSwitch(switcher, root.toPath(), "module", "dev")
        val module = root.resolve("module").apply { mkdirs() }
        git.dirty = true
        val dirty = runSwitch(switcher, root.toPath(), "module", "dev")
        git.dirty = false
        git.branch = "dev"
        val current = runSwitch(switcher, root.toPath(), "module", "dev")

        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_INITIALIZED),
            missing,
        )
        assertEquals(SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.DIRTY), dirty)
        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.ALREADY_ON_TARGET),
            current,
        )
        assertTrue(module.isDirectory)
        assertEquals(3, leaseCloseCount)
    }

    @Test
    fun `local branch is preferred and checkout result is returned`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit().apply {
            localBranchExists = true
            remoteBranchExists = true
        }
        val logs = mutableListOf<String>()
        val switcher = switcher(git, logs = logs)

        val result = runSwitch(switcher, root.toPath(), "module", "dev")

        assertTrue(result is SingleRepositorySwitchResult.Success)
        assertEquals(1, git.checkoutExistingCount)
        assertEquals(0, git.checkoutRemoteCount)
        val operationId = Regex("\\[single-switch-[0-9a-f]{8}]").find(logs.first())?.value
        assertTrue(operationId != null)
        assertTrue(logs.any { it.startsWith("$operationId operation started: root=") })
        assertTrue(logs.any { it.startsWith("$operationId operation finished: status=success") })
    }

    @Test
    fun `obsolete or unassociated retained worktree is never switched`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit().apply {
            registeredPaths = emptySet()
            localBranchExists = true
        }

        val unregistered = runSwitch(switcher(git), root.toPath(), "module", "dev")
        git.registeredPaths = setOf("module")
        git.identity = RepositoryIdentity("standalone", null)
        val unassociated = runSwitch(switcher(git), root.toPath(), "module", "dev")

        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_REGISTERED),
            unregistered,
        )
        assertEquals(
            SingleRepositorySwitchResult.Skipped(SingleRepositorySkipReason.NOT_REGISTERED),
            unassociated,
        )
        assertEquals(0, git.checkoutExistingCount)
        assertEquals(0, git.checkoutRemoteCount)
    }

    @Test
    fun `missing branch and failed checkout remain Git failures`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit()
        val switcher = switcher(git)

        val missing = runSwitch(switcher, root.toPath(), "module", "dev")
        git.localBranchExists = true
        git.checkoutResult = GitResult("checkout", 1, "", "failed")
        val failed = runSwitch(switcher, root.toPath(), "module", "dev")

        assertTrue(missing is SingleRepositorySwitchResult.GitFailure)
        assertEquals("branch dev not found", (missing as SingleRepositorySwitchResult.GitFailure).result.stderr)
        assertTrue(failed is SingleRepositorySwitchResult.GitFailure)
        assertEquals("failed", (failed as SingleRepositorySwitchResult.GitFailure).result.stderr)
    }

    @Test
    fun `cancellation and invalid path close their write leases`() = runBlocking {
        val root = temp.newFolder("root")
        root.resolve("module").mkdirs()
        val git = RecordingGit()
        var leaseCloseCount = 0
        val cancelled = runSwitch(switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
            completion = TestOperationCompletion.CANCEL_BEFORE,
        ), root.toPath(), "module", "dev")
        val invalid = runSwitch(switcher(
            git,
            tryAcquireWrite = { countingLease { leaseCloseCount++ } },
        ), root.toPath(), "../escape", "dev")

        assertEquals(SingleRepositorySwitchResult.Cancelled, cancelled)
        assertTrue(invalid is SingleRepositorySwitchResult.Unexpected)
        assertEquals(2, leaseCloseCount)
    }

    private suspend fun CoroutineScope.runSwitch(
        switcher: SingleRepositorySwitcher,
        root: java.nio.file.Path,
        path: String,
        target: String,
    ): SingleRepositorySwitchResult {
        val result = CompletableDeferred<SingleRepositorySwitchResult>()
        check(switcher.start(this, root, path, target, "Switching") { result.complete(it.result) })
        return result.await()
    }

    private fun switcher(
        git: RecordingGit,
        tryAcquireWrite: () -> AutoCloseable? = { countingLease {} },
        completion: TestOperationCompletion = TestOperationCompletion.COMPLETE,
        logs: MutableList<String>? = null,
    ) = SingleRepositorySwitcher(
        operations = TestGitOperationRunner(git.provider, completion),
        tryAcquireWrite = tryAcquireWrite,
        log = createStringAppender { message -> logs?.add(message) },
    )

    private fun countingLease(onClose: () -> Unit) = AutoCloseable(onClose)

    private class RecordingGit {
        var dirty = false
        var branch = "main"
        var localBranchExists = false
        var remoteBranchExists = false
        var checkoutResult = GitResult("checkout", 0, "", "")
        var registeredPaths: Set<String>? = null
        var identity: RepositoryIdentity? = null
        var openCount = 0
        var checkoutExistingCount = 0
        var checkoutRemoteCount = 0

        val provider = object : GitOperationProvider {
            override fun openOperation(): GitOperationSession {
                openCount++
                return newOperation()
            }
        }

        private fun newOperation(): GitOperationSession {
            return Proxy.newProxyInstance(
                GitOperationSession::class.java.classLoader,
                arrayOf(GitOperationSession::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "close", "cancel" -> null
                    "registeredSubmodules" -> (registeredPaths ?: setOf("module")).map { path ->
                        SubmoduleRegistration(path, path.substringAfterLast('/'), ".")
                    }
                    "repositoryIdentity" -> identity ?: defaultIdentity(arguments?.firstOrNull() as java.io.File)
                    "isGitRepo" -> true
                    "isDirty" -> dirty
                    "currentBranch" -> branch
                    "localBranchExists" -> localBranchExists
                    "remoteBranchExists" -> remoteBranchExists
                    "checkoutExisting" -> {
                        checkoutExistingCount++
                        checkoutResult
                    }
                    "checkoutFromRemote" -> {
                        checkoutRemoteCount++
                        checkoutResult
                    }
                    else -> defaultValue(method.returnType)
                }
            } as GitOperationSession
        }

        private fun defaultIdentity(workDir: java.io.File): RepositoryIdentity =
            if (workDir.name == "module") {
                RepositoryIdentity(
                    workDir.parentFile.resolve(".git/modules/module").canonicalPath,
                    workDir.parentFile.canonicalPath,
                )
            } else {
                RepositoryIdentity(workDir.resolve(".git").canonicalPath, null)
            }
    }

    companion object {
        private fun defaultValue(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Double.TYPE -> 0.0
            List::class.java -> emptyList<Any>()
            else -> null
        }
    }
}
