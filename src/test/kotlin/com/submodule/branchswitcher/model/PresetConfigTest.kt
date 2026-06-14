package com.submodule.branchswitcher.model

import org.junit.Test
import org.junit.Assert.*

class PresetConfigTest {

    @Test
    fun `preset targets includes main and submodules`() {
        val preset = Preset("test", "main", mapOf("SubA" to "dev", "SubB" to "feature"))
        val targets = preset.targets()
        assertEquals(3, targets.size)
        assertEquals(RepoTarget(".", "main"), targets[0])
        assertEquals(RepoTarget("SubA", "dev"), targets[1])
        assertEquals(RepoTarget("SubB", "feature"), targets[2])
    }

    @Test
    fun `preset targets with no submodules`() {
        val preset = Preset("solo", "master")
        val targets = preset.targets()
        assertEquals(1, targets.size)
        assertEquals(RepoTarget(".", "master"), targets[0])
    }

    @Test
    fun `preset id is preserved on copy`() {
        val original = Preset("test", "main")
        val renamed = original.copy(name = "renamed")
        // Stable ID must survive rename so undo history remains valid
        assertEquals(original.id, renamed.id)
        assertEquals("renamed", renamed.name)
        assertEquals("main", renamed.main)
    }

    // ── effectiveOptions ──────────────────────────────────────────

    private val globalOpts = SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true)

    @Test fun `null overrides returns global unchanged`() {
        assertEquals(globalOpts, null.effectiveOptions(globalOpts))
    }

    @Test fun `all overrides set replaces global`() {
        val ov = PresetOverrides(dirty = DirtyAction.Force, pull = false, fetchFirst = false)
        val result = ov.effectiveOptions(globalOpts)
        assertEquals(DirtyAction.Force, result.dirty)
        assertFalse(result.pull)
        assertFalse(result.fetchFirst)
    }

    @Test fun `partial overrides merge with global`() {
        val ov = PresetOverrides(dirty = DirtyAction.Skip)
        val result = ov.effectiveOptions(globalOpts)
        assertEquals(DirtyAction.Skip, result.dirty)
        assertTrue(result.pull) // from global
        assertTrue(result.fetchFirst) // from global
    }

    @Test fun `confirmBeforeInit always from global`() {
        val ov = PresetOverrides(pull = false)
        val result = ov.effectiveOptions(SwitchOptions(confirmBeforeInit = true))
        assertTrue(result.confirmBeforeInit)
        val result2 = ov.effectiveOptions(SwitchOptions(confirmBeforeInit = false))
        assertFalse(result2.confirmBeforeInit)
    }

    // ── ResolvedSwitchRequest ─────────────────────────────────────

    @Test fun `request resolves preset overrides over global`() {
        val preset = Preset("test", "main", overrides = PresetOverrides(pull = false))
        val request = ResolvedSwitchRequest.resolve(preset, globalOpts)
        assertEquals(preset, request.preset)
        assertFalse(request.options.pull)
        assertEquals(globalOpts.dirty, request.options.dirty)
    }

    @Test fun `request snapshot consistent`() {
        val preset = Preset("test", "main", overrides = PresetOverrides(dirty = DirtyAction.Force))
        val request = ResolvedSwitchRequest.resolve(preset, globalOpts)
        assertEquals(DirtyAction.Force, request.options.dirty)
        assertEquals(preset, request.preset)
    }
}
