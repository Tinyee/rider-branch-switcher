package com.submodule.branchswitcher.settings

import com.submodule.branchswitcher.model.DirtyAction
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRulesTest {

    @Test
    fun `dirty action mapping round trips every supported value`() {
        DirtyAction.entries.forEach { action ->
            assertEquals(action, indexToDirtyAction(dirtyActionToIndex(action)))
        }
    }

    @Test
    fun `invalid dirty action indexes fall back to stash`() {
        assertEquals(DirtyAction.Stash, indexToDirtyAction(-1))
        assertEquals(DirtyAction.Stash, indexToDirtyAction(99))
    }

    @Test
    fun `timeout mapping round trips supported values and defaults to 60 seconds`() {
        listOf(30, 60, 120, 300).forEach { timeout ->
            assertEquals(timeout, indexToTimeout(timeoutToIndex(timeout)))
        }
        assertEquals(60, indexToTimeout(-1))
        assertEquals(1, timeoutToIndex(999))
    }
}
