package com.submodule.branchswitcher.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutSwitchRulesTest {

    @Test
    fun `shortcut decision distinguishes load failure empty and ready states`() {
        assertEquals(ShortcutPresetLoadDecision.LoadFailed, shortcutPresetLoadDecision(false, 2))
        assertEquals(ShortcutPresetLoadDecision.NoPresets, shortcutPresetLoadDecision(true, 0))
        assertEquals(ShortcutPresetLoadDecision.Ready, shortcutPresetLoadDecision(true, 1))
    }
}
