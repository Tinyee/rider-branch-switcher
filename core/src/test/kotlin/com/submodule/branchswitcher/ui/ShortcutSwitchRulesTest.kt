package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutSwitchRulesTest {

    @Test
    fun `failed preset load blocks shortcut even with cached presets`() {
        assertEquals(ShortcutPresetLoadDecision.LoadFailed, shortcutPresetLoadDecision(false, 2))
    }

    @Test
    fun `successful empty preset load shows no-presets state`() {
        assertEquals(ShortcutPresetLoadDecision.NoPresets, shortcutPresetLoadDecision(true, 0))
    }

    @Test
    fun `successful load with presets permits selection`() {
        assertEquals(ShortcutPresetLoadDecision.Ready, shortcutPresetLoadDecision(true, 1))
    }
}
