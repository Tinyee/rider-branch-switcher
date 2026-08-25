package com.submodule.branchswitcher.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetEditorControlStateTest {

    private fun state(
        draft: Boolean = false,
        initializing: Boolean = false,
        loadingCount: Int = 0,
        persistence: Boolean = false,
        mutation: Boolean = true,
    ) = presetEditorControlState(
        hasDraftChanges = draft,
        initializing = initializing,
        loadingCount = loadingCount,
        persistenceInProgress = persistence,
        mutationActionsEnabled = mutation,
    )

    @Test
    fun `cold start has no draft so only mutation actions are enabled`() {
        val s = state(draft = false)
        assertFalse(s.saveEnabled)
        assertFalse(s.revertEnabled)
        assertTrue(s.switchEnabled)
        assertTrue(s.deriveEnabled)
    }

    @Test
    fun `a draft enables save and revert alongside mutation actions`() {
        val s = state(draft = true)
        assertTrue(s.saveEnabled)
        assertTrue(s.revertEnabled)
        assertTrue(s.switchEnabled)
        assertTrue(s.deriveEnabled)
    }

    @Test
    fun `editor-local busy from any source disables all actions`() {
        listOf(
            state(draft = true, initializing = true),
            state(draft = true, loadingCount = 1),
            state(draft = true, persistence = true),
        ).forEach { s ->
            assertFalse("save disabled while busy", s.saveEnabled)
            assertFalse("revert disabled while busy", s.revertEnabled)
            assertFalse("switch disabled while busy", s.switchEnabled)
            assertFalse("derive disabled while busy", s.deriveEnabled)
        }
    }

    @Test
    fun `global mutation gate disables only switch and derive`() {
        // An editor-local save is not gated by an unrelated repository write.
        val s = state(draft = true, mutation = false)
        assertTrue(s.saveEnabled)
        assertTrue(s.revertEnabled)
        assertFalse(s.switchEnabled)
        assertFalse(s.deriveEnabled)
    }

    @Test
    fun `mutation ending does not re-enable a still-loading editor`() {
        // Busy from a branch load holds even after the global mutation gate is released.
        val s = state(draft = true, loadingCount = 1, mutation = true)
        assertFalse(s.switchEnabled)
        assertFalse(s.deriveEnabled)
    }

    @Test
    fun `persistence ending does not re-enable while global mutation still blocks`() {
        val s = state(draft = true, persistence = false, mutation = false)
        assertTrue(s.saveEnabled)
        assertTrue(s.revertEnabled)
        assertFalse(s.switchEnabled)
        assertFalse(s.deriveEnabled)
    }

    @Test
    fun `save and revert track the draft while switch and derive track the mutation gate`() {
        assertEquals(
            PresetEditorControlState(
                saveEnabled = true,
                revertEnabled = true,
                switchEnabled = false,
                deriveEnabled = false,
            ),
            state(draft = true, mutation = false),
        )
        assertEquals(
            PresetEditorControlState(
                saveEnabled = false,
                revertEnabled = false,
                switchEnabled = true,
                deriveEnabled = true,
            ),
            state(draft = false, mutation = true),
        )
    }
}
