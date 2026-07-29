package com.submodule.branchswitcher.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiLayoutRulesTest {
    @Test
    fun `secondary action is visible before layout and when enough width is available`() {
        assertTrue(shouldShowSecondaryAction(0, 200))
        assertFalse(shouldShowSecondaryAction(199, 200))
        assertTrue(shouldShowSecondaryAction(200, 200))
    }
}
