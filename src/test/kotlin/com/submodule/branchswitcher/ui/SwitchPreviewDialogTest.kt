package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPreviewDialogTest {

    private val globalOpts = SwitchOptions(DirtyAction.Stash, pull = true, fetchFirst = true)

    private fun makeRequest(
        dirty: DirtyAction = DirtyAction.Stash,
    ): ResolvedSwitchRequest = ResolvedSwitchRequest.resolve(
        Preset("test", "main", overrides = if (dirty != DirtyAction.Stash)
            com.submodule.branchswitcher.model.PresetOverrides(dirty = dirty) else null),
        globalOpts,
    )

    private fun row(exists: Boolean = true, dirtyCount: Int = 0) =
        PreflightRow("test", ".", "main", exists, "main", dirtyCount, true, true)

    @Test
    fun `Force with dirty repo shows warning`() {
        val request = makeRequest(DirtyAction.Force)
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = 3))))
    }

    @Test
    fun `Force with clean repos does not show warning`() {
        val request = makeRequest(DirtyAction.Force)
        assertFalse(shouldShowForceWarning(request, listOf(row(dirtyCount = 0))))
    }

    @Test
    fun `Force with existing repo dirtyCount unknown shows warning`() {
        val request = makeRequest(DirtyAction.Force)
        assertTrue(shouldShowForceWarning(request, listOf(row(dirtyCount = -1))))
    }

    @Test
    fun `Force with non-existing dir does not show warning`() {
        val request = makeRequest(DirtyAction.Force)
        assertFalse(shouldShowForceWarning(request, listOf(row(exists = false, dirtyCount = -1))))
    }

    @Test
    fun `non-Force with dirty repo does not show warning`() {
        val request = makeRequest(DirtyAction.Stash)
        assertFalse(shouldShowForceWarning(request, listOf(row(dirtyCount = 5))))
    }
}
