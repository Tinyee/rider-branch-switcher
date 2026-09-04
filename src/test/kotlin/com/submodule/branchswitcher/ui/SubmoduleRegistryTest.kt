package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.log.createStringAppender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubmoduleRegistryTest {

    // Windows CI runners can be slow to schedule the shared IO dispatcher, so keep a
    // generous window; a healthy resolution still completes in milliseconds.
    private val loadCompletionTimeoutSeconds = 30L

    @Test
    fun `resolve is single-flight, broadcasts once, and is a no-op after success`() {
        val root = Paths.get(".")
        val queries = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val broadcasts = mutableListOf<Map<String, String?>>()
        val registry = SubmoduleRegistry(
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                session {
                    queries.incrementAndGet()
                    started.countDown()
                    release.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS)
                    registrations(mapOf("SubA" to "https://example.com/a.git", "SubB" to null))
                }
            },
            log = createStringAppender {},
            scheduleEdt = { it() },
        )
        registry.onResolved = { broadcasts += it }

        registry.resolve(root)
        assertTrue("first resolve must start its query", started.await(loadCompletionTimeoutSeconds, TimeUnit.SECONDS))
        // Second resolve while the first is still in flight must not start another query.
        registry.resolve(root)

        release.countDown()
        awaitUntil { broadcasts.size == 1 }

        assertEquals("the second resolve must not re-query", 1, queries.get())
        val snapshot = broadcasts.single()
        assertEquals(
            mapOf("SubA" to "https://example.com/a.git", "SubB" to null),
            snapshot,
        )
        assertEquals(
            mapOf("SubA" to "https://example.com/a.git", "SubB" to null),
            registry.urls,
        )

        // A resolve after success is a no-op: no extra query, no extra broadcast.
        registry.resolve(root)
        assertEquals(1, queries.get())
        assertEquals(1, broadcasts.size)
    }

    @Test
    fun `failed resolve stays retryable and never broadcasts`() {
        val root = Paths.get(".")
        val queries = AtomicInteger(0)
        var broadcast: Map<String, String?>? = null
        val completions = AtomicInteger(0)
        val registry = SubmoduleRegistry(
            branchLoads = BranchLoadCoordinator(CoroutineScope(Dispatchers.Unconfined)) {
                session {
                    if (queries.getAndIncrement() == 0) error("cannot read .gitmodules")
                    registrations(mapOf("SubA" to "https://example.com/a.git"))
                }
            },
            log = createStringAppender {},
            scheduleEdt = { it(); completions.incrementAndGet() },
        )
        registry.onResolved = { broadcast = it }

        registry.resolve(root)
        awaitUntil { completions.get() >= 1 }
        assertEquals(1, queries.get())
        assertNull("a failed resolve must not broadcast", broadcast)
        assertEquals("a failed resolve must not set urls", emptyMap<String, String?>(), registry.urls)

        registry.resolve(root)
        awaitUntil { completions.get() >= 2 }
        assertEquals("the retry must re-query", 2, queries.get())
        assertEquals(mapOf("SubA" to "https://example.com/a.git"), broadcast)
    }

    private fun registrations(urls: Map<String, String?>): List<SubmoduleRegistration> =
        urls.map { (path, url) -> SubmoduleRegistration(path, sectionName = path, parentPath = "", url = url) }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(loadCompletionTimeoutSeconds)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("condition not met within ${loadCompletionTimeoutSeconds}s", condition())
    }

    /** Proxy Git session answering only `registeredSubmodules`; other methods return defaults. */
    private fun session(registered: () -> List<SubmoduleRegistration>): GitOperationSession =
        Proxy.newProxyInstance(
            GitOperationSession::class.java.classLoader,
            arrayOf(GitOperationSession::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "registeredSubmodules" -> registered()
                "cancel", "close" -> Unit
                else -> when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    List::class.java -> emptyList<Any>()
                    else -> null
                }
            }
        } as GitOperationSession
}
