package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPreviewRulesTest {

    private fun makeRequest(
        dirty: DirtyAction = DirtyAction.Stash,
    ): ResolvedSwitchRequest = ResolvedSwitchRequest.resolve(
        Preset("test", "main"),
        SwitchOptions(dirty = dirty, pull = true, fetchFirst = true),
    )

    private fun row(exists: Boolean = true, dirtyCount: Int = 0) =
        PreflightRow("test", ".", "main", exists, "main", dirtyCount, true, true)

    @Test
    fun `force warning follows strategy repository and dirty state`() {
        val request = makeRequest(DirtyAction.Force)
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = 3))))
        assertFalse(shouldShowForceWarning(request, listOf(row(dirtyCount = 0))))
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = -1))))
        assertFalse(shouldShowForceWarning(request, listOf(row(exists = false, dirtyCount = -1))))
        assertFalse(shouldShowForceWarning(makeRequest(DirtyAction.Stash), listOf(row(dirtyCount = 5))))
    }
}
