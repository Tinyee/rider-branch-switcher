package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolWindowLogPanelTest {
    @Test
    fun `operation filter groups phases under the same write id and ignores refresh reads`() {
        assertEquals(
            "switch-a1b2c3d4",
            ToolWindowLogPanel.operationIdFrom("[switch-a1b2c3d4/preflight] operation started"),
        )
        assertEquals(
            "derive-1234abcd",
            ToolWindowLogPanel.operationIdFrom("[derive-1234abcd] operation finished"),
        )
        assertNull(ToolWindowLogPanel.operationIdFrom("[state-refresh-a1b2c3d4/detect] completed"))
    }
}
