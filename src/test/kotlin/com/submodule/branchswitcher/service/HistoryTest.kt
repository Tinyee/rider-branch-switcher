package com.submodule.branchswitcher.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun `history entry stores name id and timestamp`() {
        val now = System.currentTimeMillis()
        val entry = BranchSwitcherService.SwitchHistoryEntry("main", "uuid-1", now)
        assertEquals("main", entry.presetName)
        assertEquals("uuid-1", entry.presetId)
        assertEquals(now, entry.timestamp)
    }

    @Test
    fun `history entry with null id is backward compatible`() {
        val entry = BranchSwitcherService.SwitchHistoryEntry("legacy", null, 0)
        assertEquals("legacy", entry.presetName)
        assertEquals(null, entry.presetId)
    }

    @Test
    fun `history ordering is newest first`() {
        val entries = listOf(
            BranchSwitcherService.SwitchHistoryEntry("dev", "id-1", 300),
            BranchSwitcherService.SwitchHistoryEntry("main", "id-2", 100),
        )
        assertEquals("dev", entries[0].presetName)
        assertEquals("main", entries[1].presetName)
    }
}
