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

    @Test
    fun `resolveSwitchRequest merges preset overrides`() {
        service.dirtyAction = DirtyAction.Stash
        service.pullAfterSwitch = true

        val preset = com.submodule.branchswitcher.model.Preset(
            "test", "main",
            overrides = com.submodule.branchswitcher.model.PresetOverrides(pull = false),
        )
        val request = service.resolveSwitchRequest(preset)
        assertFalse("override should win over global", request.options.pull)
        assertEquals(DirtyAction.Stash, request.options.dirty)
    }

    // ── Telemetry ──────────────────────────────────────────────────────

    @Test
    fun `telemetry is opt-out by default`() {
        assertFalse("opt-in should default to false", service.telemetryOptIn)
    }

    @Test
    fun `counters do not increment when opt-out`() {
        service.telemetryOptIn = false
        service.incrementSwitchCount()
        service.incrementCreateCount()
        service.incrementDeriveCount()
        service.incrementErrorCount()
        val stats = service.exportTelemetry()
        assertTrue(stats.contains("\"switch\": 0"))
        assertTrue(stats.contains("\"createPreset\": 0"))
        assertTrue(stats.contains("\"error\": 0"))
    }

    @Test
    fun `counters increment when opt-in`() {
        service.telemetryOptIn = true
        service.incrementSwitchCount()
        service.incrementSwitchCount()
        service.incrementCreateCount()
        service.incrementDeriveCount()
        val stats = service.exportTelemetry()
        assertTrue(stats.contains("\"switch\": 2"))
        assertTrue(stats.contains("\"createPreset\": 1"))
        assertTrue(stats.contains("\"derive\": 1"))
    }

    @Test
    fun `install ID is not generated before opt-in`() {
        service.telemetryOptIn = false
        assertEquals("<not opted in>", service.telemetryInstallId)
    }

    @Test
    fun `install ID is stable after opt-in`() {
        service.telemetryOptIn = true
        val id1 = service.telemetryInstallId
        val id2 = service.telemetryInstallId
        assertEquals(id1, id2)
        assertTrue("install ID should be a UUID", id1.length > 30)
    }

    @Test
    fun `export after opt-in includes redacted non-empty install ID`() {
        service.telemetryOptIn = true
        val stats = service.exportTelemetry()
        assertTrue(stats.contains("\"pluginVersion\": \"0.7.0\""))
        assertTrue(stats.contains("\"riderVersion\":"))
        // installId should be redacted with 8-char prefix + ellipsis, not empty
        val idMatch = Regex("\"installId\": \"([^\"]+)\"").find(stats)
        assertNotNull("installId field must be present", idMatch)
        val id = idMatch!!.groupValues[1]
        assertTrue("installId must not be empty", id.isNotEmpty())
        assertTrue("installId must not start with <not", !id.startsWith("<not"))
    }

    @Test
    fun `export after opt-out does not expose persisted ID`() {
        // Simulate: user opts in, ID is generated, then user opts out
        service.telemetryOptIn = true
        val realId = service.telemetryInstallId // trigger UUID generation
        service.telemetryOptIn = false
        val stats = service.exportTelemetry()
        // Export must not contain the real UUID (even as a prefix)
        assertFalse("export must not leak real UUID after opt-out",
            stats.contains(realId.take(8)))
    }

    @Test
    fun `prompt shown flag prevents re-prompting`() {
        assertFalse("prompt not shown by default", service.telemetryPromptShown)
        service.telemetryPromptShown = true
        assertTrue(service.telemetryPromptShown)
    }

    @Test
    fun `loadState preserves telemetry fields`() {
        val state = BranchSwitcherService.OptionsState(
            telemetryInstallId = "test-id-123",
            telemetryOptIn = true,
            telemetrySwitchCount = 42,
        )
        service.loadState(state)
        assertTrue(service.telemetryOptIn)
        assertEquals("test-id-123", service.telemetryInstallId)
        val stats = service.exportTelemetry()
        assertTrue(stats.contains("\"switch\": 42"))
    }
}
