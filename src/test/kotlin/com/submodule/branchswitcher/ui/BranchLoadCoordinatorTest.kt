package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
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

    private fun fetchOkSession(): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "fetch" -> GitResult("fetch", 0, "", "")
                "listAllBranches" -> listOf("dev", "release")
                "cancel" -> Unit
                "close" -> Unit
                else -> null
            }
        } as GitOperationSession

    @Test
    fun `refresh fetches then relists and reports success`() {
        val done = CountDownLatch(1)
        var outcome: Result<BranchRefreshResult>? = null
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            fetchOkSession()
        }
        coordinator.refresh(File("sub")) {
            outcome = it
            done.countDown()
        }
        assertTrue("refresh should complete", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        val result = requireNotNull(outcome).getOrThrow()
        assertTrue(result.succeeded)
        assertEquals(listOf("dev", "release"), result.branches)
    }

    @Test
    fun `refresh reports a non-ok fetch as succeeded=false without throwing`() {
        val done = CountDownLatch(1)
        var outcome: Result<BranchRefreshResult>? = null
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            Proxy.newProxyInstance(
                GitOperationSession::class.java.classLoader,
                arrayOf(GitOperationSession::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "fetch" -> GitResult("fetch", 1, "", "cannot fetch")
                    "listAllBranches" -> emptyList<String>()
                    "cancel" -> Unit
                    "close" -> Unit
                    else -> null
                }
            } as GitOperationSession
        }
        coordinator.refresh(File("sub")) {
            outcome = it
            done.countDown()
        }
        assertTrue("refresh should complete", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        assertFalse("fetch failure must surface as succeeded=false", requireNotNull(outcome).getOrThrow().succeeded)
    }

    @Test
    fun `refreshAll fetches every dir sequentially and splits outcomes`() {
        val fetched = mutableListOf<String>()
        val done = CountDownLatch(1)
        var outcome: Result<RefreshAllOutcome>? = null
        val coordinator = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined), maxConcurrentLoads = 1) {
            Proxy.newProxyInstance(
                GitOperationSession::class.java.classLoader,
                arrayOf(GitOperationSession::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "fetch" -> {
                        val dir = (args?.firstOrNull() as File).name
                        fetched += dir
                        if (dir == "c") GitResult("fetch", 1, "", "boom")
                        else GitResult("fetch", 0, "", "")
                    }
                    "cancel" -> Unit
                    "close" -> Unit
                    else -> null
                }
            } as GitOperationSession
        }
        coordinator.refreshAll(listOf(File("a"), File("b"), File("c"))) {
            outcome = it
            done.countDown()
        }
        assertTrue("refreshAll should complete", done.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        val result = requireNotNull(outcome).getOrThrow()
        assertEquals(listOf("a", "b"), result.succeeded.map { it.name })
        assertEquals(listOf("c"), result.failures.map { it.first.name })
        assertEquals(listOf("a", "b", "c"), fetched)
    }
}
