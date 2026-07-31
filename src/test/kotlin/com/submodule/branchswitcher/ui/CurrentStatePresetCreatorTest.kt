package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentStatePresetCreatorTest {
    @Test
    fun `current state preset requires a main branch and every repository`() {
        assertEquals(
            CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE,
            currentStatePresetBlockReason(null, emptyList()),
        )
        assertEquals(
            CurrentStatePresetBlockReason.MAIN_BRANCH_UNAVAILABLE,
            currentStatePresetBlockReason("", listOf("SubA (detached)")),
        )
        assertEquals(
            CurrentStatePresetBlockReason.INCOMPLETE_REPOSITORIES,
            currentStatePresetBlockReason("main", listOf("SubA (not initialized)")),
        )
        assertNull(currentStatePresetBlockReason("main", emptyList()))
    }
}
