package com.submodule.branchswitcher.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun `history entry stores name and timestamp`() {
        val now = System.currentTimeMillis()
        val entry = BranchSwitcherService.SwitchHistoryEntry("main", now)
        assertEquals("main", entry.presetName)
        assertEquals(now, entry.timestamp)
    }

    @Test
    fun `history ordering is newest first`() {
        val entries = listOf(
            BranchSwitcherService.SwitchHistoryEntry("dev", 300),
            BranchSwitcherService.SwitchHistoryEntry("main", 100),
        )
        assertEquals("dev", entries[0].presetName)
        assertEquals("main", entries[1].presetName)
    }
}
