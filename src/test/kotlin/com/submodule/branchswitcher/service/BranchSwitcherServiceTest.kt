package com.submodule.branchswitcher.service

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.model.DirtyAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [BranchSwitcherService] core logic without IntelliJ runtime.
 * Uses [Proxy.newProxyInstance] for [Project] — same pattern as other tests
 * in this project (see BranchComboUtilTest), avoids pulling in a mocking framework.
 */
class BranchSwitcherServiceTest {

    private val testScope = CoroutineScope(Dispatchers.Default)
    private lateinit var service: BranchSwitcherService

    /** Project mock — no method is ever called, it is only stored as a reference. */
    private val stubProject: Project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, _, _ -> null } as Project

    @Before
    fun setUp() {
        service = BranchSwitcherService(stubProject, testScope)
    }

    // ── Write gate ───────────────────────────────────────────────────

    @Test
    fun `write lease can be acquired only once until released`() {
        val lease = service.tryAcquireWrite()
        assertNotNull("first acquisition should succeed", lease)
        assertNull("second acquisition should fail while held", service.tryAcquireWrite())
        assertNull("third acquisition should still fail", service.tryAcquireWrite())
        lease?.close()
    }

    @Test
    fun `closing write lease allows reacquisition`() {
        service.tryAcquireWrite()?.close()
        assertNotNull("should re-acquire after release", service.tryAcquireWrite())
    }

    @Test
    fun `old write lease cannot release a newly acquired lease`() {
        val oldLease = requireNotNull(service.tryAcquireWrite())
        oldLease.close()
        val newLease = requireNotNull(service.tryAcquireWrite())

        oldLease.close()

        assertNull("new lease must remain held", service.tryAcquireWrite())
        newLease.close()
    }

    // ── Switch history ───────────────────────────────────────────────

    @Test
    fun `addHistory inserts newest at front`() {
        service.addHistory("c")
        service.addHistory("b")
        service.addHistory("a")
        val names = service.getHistory().map { it.presetName }
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `addHistory caps at 5 entries`() {
        for (i in 1..7) {
            service.addHistory("preset-$i", "id-$i")
        }
        val history = service.getHistory()
        assertEquals("max 5 entries", 5, history.size)
        assertEquals("newest first", "preset-7", history[0].presetName)
        assertEquals("oldest kept", "preset-3", history[4].presetName)
    }

    @Test
    fun `addHistory stores preset id when provided`() {
        service.addHistory("dev", "uuid-abc")
        assertEquals("uuid-abc", service.getHistory().single().presetId)
    }

    @Test
    fun `addHistory id defaults to null`() {
        service.addHistory("dev")
        assertNull(service.getHistory().single().presetId)
    }

    @Test
    fun `history is empty initially`() {
        assertTrue(service.getHistory().isEmpty())
    }

    @Test
    fun `getHistory returns defensive copy`() {
        service.addHistory("a")
        val copy1 = service.getHistory()
        val copy2 = service.getHistory()
        assertNotSame("each call should return a new list", copy1, copy2)
    }

    // ── Settings getters/setters ─────────────────────────────────────

    @Test
    fun `dirtyAction defaults to Stash`() {
        assertEquals(DirtyAction.Stash, service.dirtyAction)
    }

    @Test
    fun `dirtyAction round-trips through enum name`() {
        service.dirtyAction = DirtyAction.Skip
        assertEquals(DirtyAction.Skip, service.dirtyAction)
        service.dirtyAction = DirtyAction.Force
        assertEquals(DirtyAction.Force, service.dirtyAction)
        service.dirtyAction = DirtyAction.Stash
        assertEquals(DirtyAction.Stash, service.dirtyAction)
    }

    @Test
    fun `fetchFirst defaults to true`() { assertTrue(service.fetchFirst) }

    @Test
    fun `pullAfterSwitch defaults to true`() { assertTrue(service.pullAfterSwitch) }

    @Test
    fun `timeoutSeconds defaults to 60`() { assertEquals(60, service.timeoutSeconds) }

    @Test
    fun `confirmBeforeInit defaults to false`() { assertFalse(service.confirmBeforeInit) }

    @Test
    fun `loadState restores all persisted settings`() {
        // Construct a fresh state object — does not share instances with the service.
        val state = BranchSwitcherService.OptionsState(
            dirtyAction = "Force",
            fetchFirst = false,
            pullAfterSwitch = false,
            timeoutSeconds = 120,
            confirmBeforeInit = true,
            history = mutableListOf(
                BranchSwitcherService.SwitchHistoryEntry("dev", "id-1", 1000),
                BranchSwitcherService.SwitchHistoryEntry("main", "id-2", 2000),
            ),
        )
        service.loadState(state)

        assertEquals(DirtyAction.Force, service.dirtyAction)
        assertFalse(service.fetchFirst)
        assertFalse(service.pullAfterSwitch)
        assertEquals(120, service.timeoutSeconds)
        assertTrue(service.confirmBeforeInit)
        assertEquals(2, service.getHistory().size)
    }

    // ── GitClient caching ────────────────────────────────────────────

    @Test
    fun `gitClient returns same instance for same timeout`() {
        service.timeoutSeconds = 30
        val c1 = service.gitClient
        val c2 = service.gitClient
        assertSame("same timeout should return cached instance", c1, c2)
    }

    @Test
    fun `gitClient creates new instance when timeout changes`() {
        service.timeoutSeconds = 30
        val c1 = service.gitClient
        service.timeoutSeconds = 90
        val c2 = service.gitClient
        assertNotSame("different timeout should create new instance", c1, c2)
    }

    // ── Concurrent contracts ──────────────────────────────────────────
    // Verify the concurrent observable contract of the write gate.

    @Test
    fun `concurrent write acquisition grants exactly one winner`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        val winners = AtomicInteger(0)

        try {
            val futures = (0 until threads).map {
                pool.submit<BranchSwitcherService.WriteLease?> {
                    latch.await()
                    service.tryAcquireWrite()
                }
            }
            latch.countDown() // release all threads simultaneously

            val leases = futures.map { it.get(5, TimeUnit.SECONDS) }
            leases.filterNotNull().forEach { winners.incrementAndGet() }

            assertEquals("exactly one thread should acquire the gate", 1, winners.get())
            leases.filterNotNull().forEach { it.close() }
        } finally {
            pool.shutdownNow()
        }
    }

    // ── resolveSwitchRequest ───────────────────────────────────────

    @Test
    fun `resolveSwitchRequest maps all 4 global fields`() {
        service.dirtyAction = DirtyAction.Skip
        service.pullAfterSwitch = false
        service.fetchFirst = false
        service.confirmBeforeInit = true

        val request = service.resolveSwitchRequest(com.submodule.branchswitcher.model.Preset("test", "main"))
        assertEquals(DirtyAction.Skip, request.options.dirty)
        assertFalse(request.options.pull)
        assertFalse(request.options.fetchFirst)
        assertTrue(request.options.confirmBeforeInit)
    }
}
