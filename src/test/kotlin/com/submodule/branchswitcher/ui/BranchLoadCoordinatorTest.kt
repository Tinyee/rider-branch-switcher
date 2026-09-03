package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BranchLoadCoordinatorTest {

    // Windows CI runners can be slow to schedule the shared IO dispatcher, so a
    // trivially-synchronous load may take longer than a tight 5s latch. Keep a
    // generous window; a healthy load still completes in milliseconds.
    private val loadCompletionTimeoutSeconds = 30L

    @Test
    fun `close cancels an in-flight load's git session`() {
        val firstStarted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            branchOperation(onCancel = { firstCancelled.set(true) }, load = { emptyList() })
        }

        // The load blocks until close() cancels the session; the coroutine job is then
        // cancelled too, so the block never needs to throw to unwind.
        coordinator.discover(
            block = {
                firstStarted.countDown()
                while (!firstCancelled.get()) Thread.sleep(10)
            },
            onResult = { },
        )
        assertTrue("load should start", firstStarted.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))

        coordinator.close()

        // close() cancels the coroutine job; the Git session cancellation is what
        // actually interrupts the blocking git command, so both must be exercised.
        assertTrue("close must cancel the active git session", firstCancelled.get())
    }

    @Test
    fun `close cancels a pending load still waiting for a permit`() {
        val firstStarted = CountDownLatch(1)
        val firstCancelled = AtomicBoolean(false)
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            branchOperation(onCancel = { firstCancelled.set(true) }, load = { emptyList() })
        }

        coordinator.launch {
            firstStarted.countDown()
            while (!firstCancelled.get()) Thread.sleep(10)
        }
        assertTrue("first load should start", firstStarted.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))

        // Second load never opens a session: it is queued behind the only permit.
        val pending = coordinator.launch { }
        assertTrue("second load should wait on the permit", pending.isActive)

        coordinator.close()

        assertFalse("pending load must be cancelled by close", pending.isActive)
        assertTrue("first load's session must be cancelled", firstCancelled.get())
    }

    @Test
    fun `refreshRemoteSubmodules splits succeeded and failed paths and continues past failures`() {
        val root = Files.createTempDirectory("coord-refresh")
        Files.createDirectories(root.resolve("SubGood").resolve(".git"))
        Files.createDirectories(root.resolve("SubBad").resolve(".git"))
        Files.createDirectories(root.resolve("SubUninit")) // no .git -> uninitialized

        val fetchedDirs = mutableListOf<String>()
        val listedUrls = mutableListOf<String>()
        val finished = CountDownLatch(1)
        var outcome: SubmoduleRefreshOutcome? = null
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            refreshOperation(
                fetch = { dir ->
                    fetchedDirs += dir
                    if (dir == "SubBad") GitResult("fetch", 1, "", "offline") else GitResult("fetch", 0, "", "")
                },
                listRemoteHeads = { url ->
                    listedUrls += url
                    if (url == "https://example.com/SubBad.git") error("remote unavailable") else listOf("remote-$url")
                },
                listAllBranches = { dir ->
                    if (dir == "SubBad") error("local unavailable") else listOf("local-$dir")
                },
            )
        }
        val registrations = listOf(
            SubmoduleRegistration("SubGood", "s1", "", "https://example.com/SubGood.git"),
            SubmoduleRegistration("SubBad", "s2", "", "https://example.com/SubBad.git"),
            SubmoduleRegistration("SubUninit", "s3", "", "https://example.com/SubUninit.git"),
        )

        coordinator.refreshRemoteSubmodules(
            root.toFile(),
            registrations,
            RemoteBranchCache(),
            createStringAppender {},
        ) { result ->
            outcome = result.getOrNull()
            finished.countDown()
        }

        assertTrue("refresh should finish", finished.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        val o = requireNotNull(outcome)
        assertEquals(setOf("SubGood", "SubUninit"), o.succeeded.keys)
        assertEquals(listOf("SubBad"), o.failedPaths)
        assertTrue("uninitialized submodule must skip fetch", "SubUninit" !in fetchedDirs)
        assertTrue("uninitialized submodule must still ls-remote", "https://example.com/SubUninit.git" in listedUrls)
        // SubUninit runs after the failing SubBad, so its ls-remote proves continue-on-failure.
    }

    /** Proxy Git session routing `cancel` to a callback; other methods return defaults. */
    private fun branchOperation(
        onCancel: () -> Unit = {},
        load: () -> List<String>,
    ): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listAllBranches" -> load()
                "cancel" -> onCancel()
                "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession

    /** Proxy Git session routing refresh queries (`fetch`/`listRemoteHeads`/`listAllBranches`) by dir/url. */
    private fun refreshOperation(
        onCancel: () -> Unit = {},
        fetch: (String) -> GitResult = { GitResult("fetch", 0, "", "") },
        listRemoteHeads: (String) -> List<String> = { emptyList() },
        listAllBranches: (String) -> List<String> = { emptyList() },
    ): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, args ->
            when (method.name) {
                "fetch" -> fetch((args?.get(0) as File).name)
                "listRemoteHeads" -> listRemoteHeads(args?.get(1) as String)
                "listAllBranches" -> listAllBranches((args?.get(0) as File).name)
                "cancel" -> onCancel()
                "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<String>()
                    else -> null
                }
            }
        } as GitOperationSession
}
