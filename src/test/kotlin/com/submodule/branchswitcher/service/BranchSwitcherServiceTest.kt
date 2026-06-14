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
    fun `tryStartWrite returns true once then false until released`() {
        assertTrue("first acquisition should succeed", service.tryStartWrite())
        assertFalse("second acquisition should fail (gate held)", service.tryStartWrite())
        assertFalse("third acquisition should still fail", service.tryStartWrite())
    }

    @Test
    fun `endWrite releases gate allowing next tryStartWrite`() {
        service.tryStartWrite()
        service.endWrite()
        assertTrue("should re-acquire after release", service.tryStartWrite())
    }

    @Test
    fun `endWrite is idempotent`() {
        service.endWrite()
        service.endWrite()
        assertTrue("gate should be free after idempotent endWrite", service.tryStartWrite())
    }

    // ── Detect generation (stale-detection) ──────────────────────────

    @Test
    fun `nextDetectGen increments monotonically`() {
        val g1 = service.nextDetectGen()
        val g2 = service.nextDetectGen()
        val g3 = service.nextDetectGen()
        assertTrue("g1 < g2", g1 < g2)
        assertTrue("g2 < g3", g2 < g3)
    }

    @Test
    fun `getDetectGen returns last nextDetectGen value`() {
        service.nextDetectGen() // discard
        val gen = service.nextDetectGen()
        assertEquals(gen, service.getDetectGen())
    }

    @Test
    fun `getDetectGen starts at 0 before any detection`() {
        assertEquals(0, service.getDetectGen())
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
        assertEquals("uuid-abc", service.getLastHistory()!!.presetId)
    }

    @Test
    fun `addHistory id defaults to null`() {
        service.addHistory("dev")
        assertNull(service.getLastHistory()!!.presetId)
    }

    @Test
    fun `getLastHistory returns null when no history`() {
        assertNull(service.getLastHistory())
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
    // Verify concurrent observable contracts: write gate mutual exclusion
    // and detectGen uniqueness. These are probabilistic — they exercise
    // the concurrent code path but cannot reliably prevent someone from
    // downgrading AtomicBoolean/AtomicLong to plain Boolean/Long.

    @Test
    fun `concurrent tryStartWrite grants exactly one winner`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        val winners = AtomicInteger(0)

        try {
            val futures = (0 until threads).map {
                pool.submit<Boolean> {
                    latch.await()
                    service.tryStartWrite()
                }
            }
            latch.countDown() // release all threads simultaneously

            for (f in futures) {
                if (f.get(5, TimeUnit.SECONDS)) winners.incrementAndGet()
            }

            assertEquals("exactly one thread should acquire the gate", 1, winners.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent nextDetectGen yields unique values and correct count`() {
        val threads = 8
        val callsPerThread = 100
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        val sync = Any()
        val collected = mutableListOf<Long>()

        try {
            val futures = (0 until threads).map {
                pool.submit {
                    latch.await()
                    val batch = (0 until callsPerThread).map { service.nextDetectGen() }
                    synchronized(sync) { collected.addAll(batch) }
                }
            }
            latch.countDown()

            for (f in futures) {
                f.get(5, TimeUnit.SECONDS)
            }

            val expectedCount = threads * callsPerThread
            assertEquals("all calls should produce a value", expectedCount, collected.size)
            assertEquals("all values should be unique", expectedCount, collected.distinct().size)
            // Final getDetectGen must equal the max generated value
            assertEquals(collected.max(), service.getDetectGen())
        } finally {
            pool.shutdownNow()
        }
    }
}
