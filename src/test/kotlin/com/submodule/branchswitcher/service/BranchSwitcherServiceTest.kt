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
        service.tryAcquireWrite()?.also { it.close() }
            ?: error("should re-acquire after release")
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
        service.addHistory("c", "id-c")
        service.addHistory("b", "id-b")
        service.addHistory("a", "id-a")
        val history = service.getHistory()
        val names = history.map { it.presetName }
        assertEquals(listOf("a", "b", "c"), names)
        assertEquals("id-a", history.first().presetId)
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
    fun `addHistory dedups a repeat of the newest entry`() {
        service.addHistory("a", "id-a")
        service.addHistory("a", "id-a")
        val history = service.getHistory()
        assertEquals(1, history.size)
        assertEquals("a", history[0].presetName)
    }

    @Test
    fun `addHistory keeps a genuine return to an older preset`() {
        service.addHistory("a", "id-a")
        service.addHistory("b", "id-b")
        service.addHistory("a", "id-a")
        val history = service.getHistory()
        assertEquals(listOf("a", "b", "a"), history.map { it.presetName })
    }

    @Test
    fun `addHistory dedups by id so a rename does not create a second entry`() {
        service.addHistory("new-name", "id-1")
        service.addHistory("old-name", "id-1")
        val history = service.getHistory()
        assertEquals(1, history.size)
        assertEquals("new-name", history[0].presetName)
    }

    @Test
    fun `addHistory falls back to name for legacy entries without an id`() {
        service.addHistory("main")
        service.addHistory("main")
        assertEquals(1, service.getHistory().size)
        // A legacy entry and an id-carrying entry for the same name are the same preset.
        service.addHistory("main", "id-9")
        assertEquals(1, service.getHistory().size)
    }

    // ── Settings getters/setters ─────────────────────────────────────

    @Test
    fun `settings use expected defaults`() {
        assertEquals(DirtyAction.Stash, service.dirtyAction)
        assertTrue(service.fetchFirst)
        assertTrue(service.pullAfterSwitch)
        assertEquals(60, service.timeoutSeconds)
        assertFalse(service.confirmBeforeInit)
    }

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

    @Test
    fun `persistent state is copied at the service boundary`() {
        val externalState = BranchSwitcherService.OptionsState(
            history = mutableListOf(BranchSwitcherService.SwitchHistoryEntry("main")),
        )
        service.loadState(externalState)

        externalState.history.clear()
        val exportedState = service.state
        exportedState.history.clear()

        assertEquals(listOf("main"), service.getHistory().map { it.presetName })
    }

    // ── GitClient caching ────────────────────────────────────────────

    @Test
    fun `gitClient cache follows timeout changes`() {
        service.timeoutSeconds = 30
        val c1 = service.gitClient
        val c2 = service.gitClient
        assertSame("same timeout should return cached instance", c1, c2)
        service.timeoutSeconds = 90
        assertNotSame("different timeout should create new instance", c1, service.gitClient)
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
