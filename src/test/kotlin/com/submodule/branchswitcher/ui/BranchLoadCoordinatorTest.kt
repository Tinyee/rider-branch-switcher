package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BranchLoadCoordinatorTest {

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
        assertTrue("load should start", firstStarted.await(5, TimeUnit.SECONDS))

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
        assertTrue("first load should start", firstStarted.await(5, TimeUnit.SECONDS))

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
}
