package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetEditRulesTest {
    private val saved = Preset(
        id = "preset-id",
        name = "dev",
        main = "main",
        submodules = linkedMapOf("SubA" to "dev", "SubB" to "release"),
    )

    @Test
    fun `draft applies trimmed selections and excludes removed rows`() {
        val unchangedSelection = PresetDraftSelection(
            mainBranch = "main",
            submodules = listOf(
                SubmoduleDraftSelection("SubA", "dev", included = true),
                SubmoduleDraftSelection("SubB", "release", included = true),
            ),
        )
        val selection = PresetDraftSelection(
            mainBranch = " feature ",
            submodules = listOf(
                SubmoduleDraftSelection("SubA", " topic ", included = true),
                SubmoduleDraftSelection("SubB", "release", included = false),
                SubmoduleDraftSelection("SubC", null, included = true),
            ),
        )

        assertEquals(
            saved.copy(main = "feature", submodules = linkedMapOf("SubA" to "topic", "SubC" to "")),
            selection.applyTo(saved),
        )
        // The busy gate that used to suppress this check via editingBlocked now lives in
        // presetEditorControlState; this rule answers only whether the content changed.
        assertFalse(hasPresetDraftChanges(saved, unchangedSelection))
        assertTrue(hasPresetDraftChanges(saved, selection))
    }

    @Test
    fun `rename decision ignores no-op input rejects unavailable names and preserves preset identity`() {
        assertSame(PresetRenameDecision.Ignore, decidePresetRename(saved, null) { true })
        assertSame(PresetRenameDecision.Ignore, decidePresetRename(saved, " dev ") { true })
        assertSame(PresetRenameDecision.Invalid, decidePresetRename(saved, "release") { false })

        val decision = decidePresetRename(saved, " feature ") { true } as PresetRenameDecision.Rename
        assertEquals(saved.copy(name = "feature"), decision.preset)
        assertEquals(saved.id, decision.preset.id)
    }
}
